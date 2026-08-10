import os
import subprocess
from pathlib import Path

import pytest

from release_automation.activities import run_trusted_command


@pytest.mark.asyncio
async def test_process_has_explicit_environment_and_status(tmp_path: Path) -> None:
    script = tmp_path / "check.sh"
    script.write_text(
        "#!/usr/bin/env bash\n"
        "[[ ${RELEASE_TEST_VALUE:-} == exact ]]\n"
        "[[ -z ${RELEASE_TEST_SECRET_SHOULD_NOT_LEAK:-} ]]\n"
        "printf 'ok\\n'\n"
    )
    os.environ["RELEASE_TEST_SECRET_SHOULD_NOT_LEAK"] = "secret"
    try:
        assert await run_trusted_command(tmp_path, script, {"RELEASE_TEST_VALUE": "exact"}) == [
            "ok"
        ]
    finally:
        os.environ.pop("RELEASE_TEST_SECRET_SHOULD_NOT_LEAK")

    script.write_text("#!/usr/bin/env bash\nexit 42\n")
    with pytest.raises(subprocess.CalledProcessError) as failure:
        await run_trusted_command(tmp_path, script, {})
    assert failure.value.returncode == 42
