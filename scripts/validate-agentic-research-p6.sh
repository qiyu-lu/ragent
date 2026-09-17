#!/usr/bin/env bash
set -euo pipefail
p6_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$p6_repo"
export P3_TESTS=${P6_TESTS:-ResearchEventStreamServiceTest,ResearchArtifactGeneratorTest,ResearchRunPostgresIT,ResearchWorkerCoordinatorTest,ResearchWorkerNativeTest,ResearchNativeToolsTest,ResearchBudgetTest,ResearchRunControllerTest,ResearchEvidenceToolsTest,ResearchEvidenceStoreTest}
exec bash scripts/validate-agentic-research-p3.sh
