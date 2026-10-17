# frozen_string_literal: true

require 'json'
require 'time'
require '/usr/share/logstash/ruby/cc_mask.rb'

def register(_params); end

# A document Elasticsearch rejected, read back from the Logstash dead letter queue. The event was masked
# before it was sent, so its JSON is safe to keep (truncated) as originalValue.
def filter(event)
  reason = event.get('[@metadata][dead_letter_queue][reason]').to_s
  doc = event.to_hash.reject { |k, _| k.start_with?('@') }
  topic = doc['topic'].to_s
  event_id = doc['eventId']
  corr = doc['correlationId']
  event.to_hash.each_key { |k| event.remove(k) unless k.start_with?('@') }
  event.set('eventId', event_id)
  event.set('correlationId', corr)
  event.set('topic', topic)
  event.set('source', 'logstash-es-reject')
  event.set('originalTopic', topic)
  event.set('originalValue', JSON.generate(doc)[0, 1024])
  event.set('errorClass', 'ElasticsearchRejected')
  event.set('errorMessage', reason[0, 512])
  event.set('failedAt', Time.now.utc.iso8601(3))
  event.set('[@metadata][target_alias]', 'cc-dlq-write')
  [event]
end
