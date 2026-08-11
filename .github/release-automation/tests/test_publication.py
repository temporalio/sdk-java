import hashlib
import io
import zipfile
from pathlib import Path
from typing import Any

import pytest

from release_automation.models import GithubArtifactReceipt
from release_automation.publication import Conflict, Publisher, SonatypeView


def archive(name: str, content: bytes) -> bytes:
    """Build the one-file Actions ZIP shape accepted by publication."""
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as bundle:
        bundle.writestr(name, content)
    return output.getvalue()


@pytest.mark.asyncio
async def test_download_artifact_validates_receipt_archive_and_merge_origin(
    tmp_path: Path,
) -> None:
    """Consume only the exact one-file archive recorded by the merge workflow."""
    content = b"release bytes"
    zipped = archive("release.tar.gz", content)
    digest = f"sha256:{hashlib.sha256(zipped).hexdigest()}"
    receipt = GithubArtifactReceipt(11, 22, "exact-name", digest, "release.tar.gz")
    publisher = Publisher.__new__(Publisher)
    publisher.github_token = "token"

    async def metadata(*_args: Any, **_options: Any) -> tuple[int, Any]:
        """Return exact GitHub metadata without making an external request."""
        return 200, {
            "id": 11,
            "workflow_run": {"id": 22},
            "name": "exact-name",
            "digest": digest,
            "expired": False,
        }

    run_data = {
        "id": 22,
        "path": ".github/workflows/temporal-release-candidate.yml",
        "event": "push",
        "head_repository": {"full_name": "temporalio/sdk-java"},
        "head_branch": "main",
        "status": "completed",
    }

    async def run(*_args: Any) -> dict[str, Any]:
        """Represent the accepted merge-triggered candidate workflow run."""
        return run_data

    async def download(*_args: Any, **_options: Any) -> tuple[int, bytes]:
        """Return the digest-bound Actions archive."""
        return 200, zipped

    publisher._json_request = metadata
    publisher._gh_json = run
    publisher._request = download
    result = await publisher.download_artifact(receipt, tmp_path / "download")
    assert result.read_bytes() == content

    run_data["path"] = ".github/workflows/stale-scheduled-release.yml"
    with pytest.raises(Conflict, match="another workflow run"):
        await publisher.download_artifact(receipt, tmp_path / "stale")
    run_data["path"] = ".github/workflows/temporal-release-candidate.yml"
    receipt.fileName = "other.tar.gz"
    with pytest.raises(Conflict, match="contents differ"):
        await publisher.download_artifact(receipt, tmp_path / "conflict")


def test_sonatype_discovery_refuses_duplicate_generation_identity() -> None:
    """Join the two Sonatype APIs without arbitrarily choosing duplicate matches."""
    view = SonatypeView(
        [{"repositoryId": "first", "description": "release:0"}],
        [{"key": "second", "description": "release:0"}],
    )
    with pytest.raises(Conflict, match="Multiple Sonatype repositories"):
        view.find("release:0")


@pytest.mark.asyncio
async def test_zero_byte_github_asset_is_a_conflict(tmp_path: Path) -> None:
    """Do not retain the obsolete convention that deleted zero-byte starter assets."""
    publisher = Publisher.__new__(Publisher)
    publisher.tag = "v1.2.3"
    publisher.commit = "0123456789abcdef0123456789abcdef01234567"
    publisher.notes = tmp_path / "notes"
    publisher.notes.write_text("notes")
    publisher.assets = tmp_path / "assets"
    publisher.assets.mkdir()
    (publisher.assets / "release.tar.gz").write_bytes(b"exact")
    release = {
        "tag_name": publisher.tag,
        "name": publisher.tag,
        "body": "notes",
        "draft": True,
        "prerelease": False,
        "target_commitish": publisher.commit,
        "assets": [
            {
                "id": 1,
                "name": "release.tar.gz",
                "state": "starter",
                "size": 0,
                "digest": None,
            }
        ],
    }

    async def ensure_tag() -> None:
        """Represent the already reconciled exact tag."""

    async def release_json() -> dict[str, Any]:
        """Return the pre-existing draft with a mismatched asset."""
        return release

    publisher.ensure_tag = ensure_tag
    publisher.release_json = release_json
    with pytest.raises(Conflict, match="asset release.tar.gz differs"):
        await publisher.reconcile_github_draft()
