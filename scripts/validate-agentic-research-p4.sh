#!/usr/bin/env bash
set -euo pipefail
p4_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$p4_repo"
# Reuse the existing disposable PostgreSQL database guard and cleanup.
export P3_TESTS=${P4_TESTS:-ResearchRunPostgresIT,ResearchWorkerCoordinatorTest,ResearchWorkerNativeTest,ResearchNativeToolsTest,ResearchBudgetTest,ResearchRunControllerTest,ResearchEvidenceToolsTest,ResearchEvidenceStoreTest}
exec bash scripts/validate-agentic-research-p3.sh
