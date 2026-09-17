#!/usr/bin/env bash
set -euo pipefail
p5_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$p5_repo"
export P3_TESTS=${P5_TESTS:-ResearchArtifactGeneratorTest,ResearchRunPostgresIT,ResearchWorkerCoordinatorTest,ResearchWorkerNativeTest,ResearchNativeToolsTest,ResearchBudgetTest,ResearchRunControllerTest,ResearchEvidenceToolsTest,ResearchEvidenceStoreTest}
exec bash scripts/validate-agentic-research-p3.sh
