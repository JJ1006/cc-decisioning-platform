# frozen_string_literal: true

require 'json'
require 'time'
require 'digest'
require '/usr/share/logstash/ruby/cc_mask.rb'

ALIASES = {
  'cc.audit.v1' => 'cc-audit-write',
  'cc.notifications.v1' => 'cc-notifications-write'
}.freeze

def register(_params); end

# Bands (coarse, for analytics and fairness; labels are zero-padded so they sort as text) of the values the rules decide on; the boundaries are those of the decision tables in docs/rules-catalog.md.
def score_band(score)
  return nil unless score.is_a?(Numeric)
  return '300-559' if score < 560
  return '560-619' if score < 620
  return '620-679' if score < 680
  return '680-739' if score < 740

  '740-900'
end

def tds_band(tds)
  return nil unless tds.is_a?(Numeric)
  return '0.00-0.35' if tds <= 0.35
  return '0.35-0.42' if tds <= 0.42
  return '0.42-0.50' if tds <= 0.50
  return '0.50-0.60' if tds <= 0.60

  '0.60+'
end

def income_band(income)
  return nil unless income.is_a?(Numeric)
  return '000-039k' if income < 40_000
  return '040-059k' if income < 60_000
  return '060-099k' if income < 100_000
  return '100-149k' if income < 150_000

  '150k+'
end

def limit_band(limit)
  return nil unless limit.is_a?(Numeric)
  return '00000-02499' if limit < 2500
  return '02500-04999' if limit < 5000
  return '05000-09999' if limit < 10_000
  return '10000-19999' if limit < 20_000

  '20000+'
end

# Turns one Kafka record (field "message", topic in [@metadata][kafka][topic]) into an indexable document.
def filter(event)
  raw = event.get('message').to_s
  topic = event.get('[@metadata][kafka][topic]').to_s
  begin
    doc = JSON.parse(raw)
    raise ArgumentError, 'not an event envelope' unless doc.is_a?(Hash) && doc['eventId'] && doc.key?('payload')
  rescue JSON::ParserError, ArgumentError => e
    return [dead_letter(event, raw, topic, e)]
  end

  doc['payload'] = CcMask.mask(doc['payload'])
  doc['topic'] = topic
  event.remove('message')
  event.remove('@version')
  doc.each { |k, v| event.set(k, v) }
  begin
    event.set('@timestamp', LogStash::Timestamp.new(Time.iso8601(doc['occurredAt'])))
  rescue StandardError
    nil # keep the ingest time
  end
  target = ALIASES.fetch(topic, 'cc-decisions-write')
  # decisions carry their source: the rules engine (ODM) or an underwriter (MANUAL)
  if target == 'cc-decisions-write'
    event.set('decisionSource', topic == 'cc.decisions.manual.v1' ? 'MANUAL' : 'ODM')
  end
  event.set('[@metadata][target_alias]', target)
  return [event] unless target == 'cc-decisions-write' && topic != 'cc.decisions.manual.v1'

  split_inputs(event)
end

# An automated decision carries the inputs the rules saw. The analyst-readable cc-decisions keeps the outcome and
# only BANDS of the inputs (the exact score, TDS and income are dropped); the full inputs become a second document for the restricted index cc-decision-inputs
# (readable by the query API only: Elasticsearch roles, deploy/es/query_reader.role.json).
def split_inputs(event)
  payload = event.get('payload')
  return [event] unless payload.is_a?(Hash)

  inputs = payload.delete('inputs')
  summary = payload['applicantSummary']
  if summary.is_a?(Hash)
    summary['scoreBand'] = score_band(summary['bureauScore'])
    summary['tdsBand'] = tds_band(summary['tdsRatio'])
    summary['incomeBand'] = income_band(summary['annualIncome'])
    summary['limitBand'] = limit_band(inputs['requestedLimit']) if inputs.is_a?(Hash)
    # exact values stay in cc-decision-inputs only
    %w[bureauScore tdsRatio annualIncome].each { |k| summary.delete(k) }
    summary.compact!
  end
  event.set('payload', payload)
  return [event] unless inputs.is_a?(Hash)

  extra = event.clone
  extra.set('payload', 'applicationId' => payload['applicationId'], 'applicantToken' => inputs['applicantToken'], 'inputs' => inputs)
  extra.set('[@metadata][target_alias]', 'cc-decision-inputs-write')
  [event, extra]
end

def dead_letter(event, raw, topic, error)
  event.remove('message')
  event.set('eventId', Digest::SHA1.hexdigest(raw))
  event.set('source', 'logstash-parse')
  event.set('originalTopic', topic)
  event.set('originalValue', CcMask.redact_raw(raw))
  event.set('errorClass', error.class.name)
  event.set('errorMessage', error.message.to_s[0, 512])
  event.set('failedAt', Time.now.utc.iso8601(3))
  event.set('[@metadata][target_alias]', 'cc-dlq-write')
  event
end
