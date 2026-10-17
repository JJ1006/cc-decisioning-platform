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
  [event]
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
