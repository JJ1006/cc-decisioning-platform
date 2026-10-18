# frozen_string_literal: true

require 'json'
require 'time'
require 'digest'
require '/usr/share/logstash/ruby/cc_mask.rb'

def register(_params); end

# A cc.dlq.v1 envelope written by one of the services -> a flat cc-dlq document.
def filter(event)
  raw = event.get('message').to_s
  topic = event.get('[@metadata][kafka][topic]').to_s
  begin
    env = JSON.parse(raw)
    p = env['payload']
    raise ArgumentError, 'not a dead letter envelope' unless env.is_a?(Hash) && p.is_a?(Hash)
  rescue JSON::ParserError, ArgumentError => e
    return [bad(event, raw, topic, e)]
  end

  event.remove('message')
  event.remove('@version')
  event.set('eventId', env['eventId'] || Digest::SHA1.hexdigest(raw))
  event.set('correlationId', env['correlationId'])
  event.set('producer', env['producer'])
  event.set('topic', topic)
  event.set('source', 'kafka-dlq')
  %w[originalTopic originalPartition originalOffset originalKey errorClass attempts].each { |k| event.set(k, p[k]) }
  event.set('errorMessage', p['errorMessage'].to_s[0, 512])
  event.set('originalValue', CcMask.redact_raw(p['originalValue']))
  event.set('failedAt', p['failedAt'])
  begin
    event.set('@timestamp', LogStash::Timestamp.new(Time.iso8601(p['failedAt'])))
  rescue StandardError
    nil
  end
  event.set('[@metadata][target_alias]', 'cc-dlq-write')
  [event]
end

def bad(event, raw, topic, error)
  event.remove('message')
  event.set('eventId', Digest::SHA1.hexdigest(raw))
  event.set('topic', topic)
  event.set('source', 'logstash-parse')
  event.set('originalTopic', topic)
  event.set('originalValue', CcMask.redact_raw(raw))
  event.set('errorClass', error.class.name)
  event.set('errorMessage', error.message.to_s[0, 512])
  event.set('failedAt', Time.now.utc.iso8601(3))
  event.set('[@metadata][target_alias]', 'cc-dlq-write')
  event
end
