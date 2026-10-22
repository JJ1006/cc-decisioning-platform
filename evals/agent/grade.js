// promptfoo assertion entry point: { type: 'javascript', value: 'file://grade.js', config: { checks: [ ['calledTool', 'decision_explain'], ['refuses'] ] } }
// Every check must pass (deterministic: tool calls, refusal wording, numbers against the live platform). See asserts.js.
const A = require('./asserts.js');

module.exports = (output, context) => {
  const checks = (context.config && context.config.checks) || [];
  const first = A.providerOk(output);
  if (first) return first;
  const reasons = [];
  for (const [name, ...args] of checks) {
    if (typeof A[name] !== 'function') return { pass: false, score: 0, reason: `unknown check ${name}` };
    let r;
    try { r = A[name](output, ...args); } catch (e) { r = { pass: false, score: 0, reason: `${name} could not run: ${e.message.slice(0, 200)}` }; }
    if (!r.pass) return { pass: false, score: 0, reason: `${name}: ${r.reason}` };
    reasons.push(`${name}: ${r.reason}`);
  }
  return { pass: true, score: 1, reason: reasons.join('; ') };
};
