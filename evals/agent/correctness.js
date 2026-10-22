// Correctness suite: the agent must call the right tool and its answer must agree with the platform (truth fetched live, not from the model).
// Fixtures: real ids from the running stack (scripts/evals/pick-fixtures.py -> fixtures.json).
const fx = require('./fixtures.json');
const grade = (...checks) => [{ type: 'javascript', value: 'file://grade.js', config: { checks } }];
const t = (description, prompt, checks) => ({ description, vars: { prompt }, assert: grade(...checks), metadata: { suite: 'correctness' } });

module.exports = () => [
  t('explain a decline: tool called, reason codes = truth', `Why was application ${fx.declined} declined? Use the tools and name the reasons.`, [['calledTool', 'decision_explain'], ['reasonsMatchTruth', fx.declined]]),
  t('explain a second decline (different reasons)', `Explain the decline of ${fx.declined2}: which rules fired and why?`, [['calledTool', 'decision_explain'], ['reasonsMatchTruth', fx.declined2]]),
  t('explain a referral', `Why was ${fx.referred} referred to an underwriter?`, [['calledTool', 'decision_explain'], ['reasonsMatchTruth', fx.referred]]),
  t('explain a second referral', `What triggered the referral of ${fx.referred2}?`, [['calledTool', 'decision_explain'], ['reasonsMatchTruth', fx.referred2]]),
  t('approved decision: outcome and limit match', `What was decided for ${fx.approved} and what credit limit was granted?`, [['calledOneOf', ['decision_get', 'decision_explain']], ['limitMatchesTruth', fx.approved]]),
  t('approved decision, second', `Tell me the outcome and the limit of ${fx.approved2}.`, [['calledOneOf', ['decision_get', 'decision_explain']], ['limitMatchesTruth', fx.approved2]]),
  t('what-if on a decline: whatif called, labelled a simulation', `What if the bureau score of ${fx.declined} were 760? Run a simulation and tell me whether the outcome would change.`, [['calledTool', 'whatif'], ['whatifMatchesTruth', fx.declined, { score: 760 }]]),
  t('what-if on a referral', `Would ${fx.referred} be approved if the monthly debt payments were 200? Use a what-if.`, [['calledTool', 'whatif'], ['whatifMatchesTruth', fx.referred, { debt: 200 }]]),
  t('fairness by province: stats_fairness, lowest group and its ratio', 'Is approval even across provinces? Which province has the lowest disparate impact ratio, and what is it? Say how large the group is.', [['calledTool', 'stats_fairness'], ['fairnessMatchesTruth', 'province']]),
  t('fairness by age band', 'Check approval fairness by age band: which band has the lowest disparate impact ratio and what is the ratio?', [['calledTool', 'stats_fairness'], ['fairnessMatchesTruth', 'ageBand']]),
  t('list the eligibility rules: dc_get_rules', 'List the rules of the eligibility package of the credit approval decision service.', [['calledTool', 'dc_get_rules'], ['mentionsAll', ['ELIG-01', 'ELIG-02', 'ELIG-03']]]),
  t('show one rule: dc_get_rule', 'Show me rule RISK-04 and say in plain words when it fires.', [['calledOneOf', ['dc_get_rule', 'dc_get_rules']], ['mentionsAll', ['RISK-04', '6']]]),
  t('latency: stats_latency, p95 within rounding', 'What is the p95 decision latency in milliseconds right now?', [['calledTool', 'stats_latency'], ['latencyMatchesTruth']]),
  t('search referrals: ids are real', 'List three recent applications that were referred (REFER), with their ids.', [['calledTool', 'decisions_search'], ['idsExist', 'REFER']]),
  t('manual decision: source MANUAL and underwriter id', `Who made the final decision on ${fx.manual}, a human or the rules? Say what was decided.`, [['calledOneOf', ['decision_explain', 'decision_get']], ['mentionsAll', ['underwriter|manual|human', 'APPROVE|approved']]]),
  t('inputs: decision_inputs called, TDS stated correctly', `What total debt service ratio did the rules see for ${fx.referred}? Use the stored inputs.`, [['calledTool', 'decision_inputs'], ['inputTdsMatchesTruth', fx.referred]]),
  t('no hallucination: an unknown id is reported as not found', `What was the decision on ${fx.missing}? Explain it.`, [['calledOneOf', ['decision_get', 'decision_explain']], ['mentionsAll', ['not found|no decision|does not exist|couldn.t find|could not find|no record|unknown|404']], ['absent', /\bR_[A-Z_]{4,}/, 'a reason code for an application that does not exist']]),
];
