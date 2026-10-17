# frozen_string_literal: true

require 'json'
require 'date'

# PII masking for every event that reaches Elasticsearch. Same rules as the Java PiiMasker it replaces:
#   SIN dropped; first and last name -> initials; postal code -> FSA (first three characters);
#   date of birth -> age band; street line and employer dropped. Applied anywhere in the JSON tree.
module CcMask
  module_function

  def age_band(age)
    return '18-24' if age < 25
    return '25-34' if age < 35
    return '35-44' if age < 45
    return '45-54' if age < 55
    return '55-64' if age < 65

    '65+'
  end

  # CC_TODAY pins the date in tests so age bands are stable
  def default_today
    ENV['CC_TODAY'] ? Date.parse(ENV['CC_TODAY']) : Date.today
  end

  def mask(node, today = default_today)
    case node
    when Hash
      out = {}
      node.each { |k, v| out[k] = mask(v, today) }
      mask_object(out, today)
    when Array
      node.map { |v| mask(v, today) }
    else
      node
    end
  end

  def mask_object(h, today)
    h.delete('sin')
    h.delete('line1')
    h.delete('employer')
    initials(h, 'firstName', 'firstInitial')
    initials(h, 'lastName', 'lastInitial')
    if h['postalCode'].is_a?(String)
      pc = h['postalCode'].delete(' ')
      h['fsa'] = pc.length >= 3 ? pc[0, 3].upcase : ''
      h.delete('postalCode')
    end
    if h['dateOfBirth'].is_a?(String)
      h['ageBand'] = begin
        dob = Date.parse(h['dateOfBirth'])
        age = today.year - dob.year - ((today.month < dob.month || (today.month == dob.month && today.day < dob.day)) ? 1 : 0)
        age_band(age)
      rescue ArgumentError
        'unknown'
      end
      h.delete('dateOfBirth')
    end
    h
  end

  def initials(h, field, replacement)
    return unless h.key?(field)

    v = h[field].to_s
    h[replacement] = v.strip.empty? ? '' : v[0].upcase + '.'
    h.delete(field)
  end

  # A raw string that failed somewhere: mask it if it is JSON, otherwise redact what looks personal; always truncate.
  def redact_raw(raw, limit = 1024)
    s = raw.to_s
    begin
      parsed = JSON.parse(s)
      s = JSON.generate(mask(parsed)) if parsed.is_a?(Hash) || parsed.is_a?(Array)
    rescue JSON::ParserError
      s = s.gsub(/("(?:sin|SIN)"\s*:\s*)"[^"]*"/, '\1"***"')
           .gsub(/\b\d{3}[ -]?\d{3}[ -]?\d{3}\b/, '***')
           .gsub(/\b[A-Za-z]\d[A-Za-z][ -]?\d[A-Za-z]\d\b/, '***')
    end
    s[0, limit]
  end
end
