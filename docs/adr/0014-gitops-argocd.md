# ADR-0014: GitOps with Argo CD

* Status: accepted
* Date: 2026-10-18
* Refines: [ADR-0013](0013-kubernetes-deployment.md)

## Context and problem statement

P10 installs the charts with scripts. P11 makes Git the only way to change a cluster, with a light touch on the laptop (kind, 7 GB) and a deliberate one on OpenShift.

## Decision

* **Argo CD, app of apps.** One hand-applied `root` Application renders `deploy/argocd/apps/<env>` (a kustomization of plain Application files, in sync-wave order) and the
  AppProjects. Argo CD itself is minimal and pinned (no Dex, notifications or ApplicationSet controller).
* **Two projects with different rights.** `platform` (operators, `cc-infra`, Filebeat, observability) may create the cluster-scoped kinds those need and nothing else;
  `apps` (`cc-platform`) is limited to namespace `cc`, no cluster-scoped kinds, this repository only. A compromised workload chart cannot create a ClusterRole.
* **Environments differ in how they move, not in what they run.** kind follows `main` with automated sync, prune and self-heal. OpenShift is pinned to a release tag and has
  **no automated sync**: a promotion pull request (reviewed under CODEOWNERS) and a human pressing Sync are two separate gates.
* **The workload charts are self-contained.** Argo CD builds the dependencies of the top-level chart only, so the `cc-lib` templates are copied into every workload chart
  (`scripts/helm-sync.sh`, checked in CI) instead of being a nested file:// dependency. Filebeat and `cc-observability` are Applications of their own because they carry
  cluster-scoped objects.
* **Secrets stay outside Git and outside Argo.** `scripts/k8s-secrets.sh` on kind; Sealed Secrets or External Secrets on OpenShift (not implemented here). The Namespace is never
  pruned.
* **Image tags are data in Git.** `values/cc-platform.yaml` per environment; a bot changes only the kind files, a promotion pull request the OpenShift one. Tags and releases made with
  the default GitHub token start no workflows, so `release.yml` starts the image build and the promotion itself.

## Consequences

* Waves: namespaces -4 (not -2: the operators create Roles in `cc`), operators -3, `cc-infra` -1, `cc-platform` 0, observability 1.
* The Argo CD stack costs about 0.7 GB; on a 7 GB Docker VM the lite platform plus Argo fits only with no spare replica (a second `notification-svc` replica starved the node).
* A change in Git reaches kind within the poll interval (3 minutes, or a refresh); every manual change on kind is reverted within about half a minute.
* On OpenShift the operators come from OLM Subscriptions with manual install plans, not Helm: the supported route on that platform. Pinning `startingCSV` is left to the day the versions are chosen.
