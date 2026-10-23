"""Explicit, bounded Windows release lookup. Never executes installers."""
import json
import re
import time
from urllib.parse import urlsplit
from . import __version__
from .project_info import REPOSITORY
from .request_policy import RequestFailure, now_text

PAGE_SIZE = 30
MAX_PAGES = 10


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result: raise ValueError("duplicate-json-key")
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=pairs)


def validate_metadata(data, repository=REPOSITORY):
    if not isinstance(data, dict) or data.get("platform") != "windows-x64": raise ValueError("platform")
    version = str(data.get("version", ""))
    if not re.fullmatch(r"\d+\.\d+\.\d+", version): raise ValueError("version")
    tag = "windows-v" + version
    expected = f"https://github.com/{repository}/releases/download/{tag}/OUNet-{version}-Setup-x64.exe"
    if not repository or data.get("url") != expected: raise ValueError("source")
    sha = str(data.get("sha256", ""))
    if not re.fullmatch(r"[0-9a-fA-F]{64}", sha): raise ValueError("hash")
    remote = tuple(map(int, version.split('.')))
    local = tuple(map(int, __version__.split('.')))
    newer = remote > local
    message = "发现可用 Windows 正式版本。" if newer else (
        "当前已是最新 Windows 正式版本。" if remote == local else "当前版本高于发布源的 Windows 正式版本。")
    return {"version": version, "url": expected, "sha256": sha.lower(), "newer": newer,
            "releaseUrl": f"https://github.com/{repository}/releases/tag/{tag}",
            "notes": str(data.get("notes", ""))[:8000], "message": message,
            "state": "available" if newer else "current" if remote == local else "ahead"}


def check_update(client, repository=REPOSITORY, clock=time.monotonic):
    deadline = clock() + 20
    def get(url, stage, decoder, **kwargs):
        if clock() >= deadline: raise RequestFailure("更新检查超时，尚无法确认最新版本。")
        return client.get(url, stage, decoder, deadline=deadline, **kwargs)
    def array(text, response):
        data = strict_json(text)
        if not isinstance(data, list): raise ValueError("list")
        return data
    valid = []
    for page in range(1, MAX_PAGES + 1):
        suffix = "" if page == 1 else f"&page={page}"
        releases = get(f"https://api.github.com/repos/{repository}/releases?per_page={PAGE_SIZE}{suffix}", "检查 Windows 发布", array)
        valid.extend(r for r in releases if isinstance(r, dict) and not r.get("draft") and not r.get("prerelease")
                     and re.fullmatch(r"windows-v\d+\.\d+\.\d+", str(r.get("tag_name", ""))))
        if len(releases) < PAGE_SIZE: break
    else:
        return {"state": "incomplete", "message": "发布列表尚未读完，无法确认最新版本，请打开 GitHub 下载页。", "checkedAt": now_text()}
    if not valid:
        return {"state": "empty", "message": "尚无 Windows 正式发布，可在 GitHub 下载页查看预览版。", "checkedAt": now_text()}
    release = max(valid, key=lambda r: tuple(map(int, r["tag_name"][9:].split('.'))))
    assets = release.get("assets", [])
    if not isinstance(assets, list): raise RequestFailure("Windows 发布不完整：附件列表无效。")
    if len(assets) >= PAGE_SIZE:
        release_id = release.get("id")
        if type(release_id) is not int or release_id <= 0: raise RequestFailure("Windows 发布不完整：附件编号无效。")
        assets = []
        for page in range(1, MAX_PAGES + 1):
            batch = get(f"https://api.github.com/repos/{repository}/releases/{release_id}/assets?per_page=30&page={page}", "读取发布附件", array)
            assets.extend(batch)
            if len(batch) < PAGE_SIZE: break
        else: raise RequestFailure("附件列表尚未读完，无法确认更新。")
    def unique(name):
        matches = [a for a in assets if isinstance(a, dict) and a.get("name") == name]
        if len(matches) != 1: raise RequestFailure("Windows 发布不完整：缺少或重复 " + name)
        asset = matches[0]
        if asset.get("state", "uploaded") != "uploaded" or asset.get("size", 1) <= 0:
            raise RequestFailure("Windows 发布附件尚未上传完成。")
        return asset
    metadata_asset = unique("windows-update.json")
    version = release["tag_name"][9:]
    installer = unique(f"OUNet-{version}-Setup-x64.exe")
    expected_url = f"https://github.com/{repository}/releases/download/{release['tag_name']}/{installer['name']}"
    if installer.get("browser_download_url") != expected_url: raise RequestFailure("Windows 安装附件来源不符。")
    asset_id = metadata_asset.get("id")
    if type(asset_id) is not int or asset_id <= 0: raise RequestFailure("Windows 更新元数据附件编号无效。")
    def decode(text, response):
        if response.status_code == 200:
            value = validate_metadata(strict_json(text), repository)
            if value["version"] != version: raise ValueError("release-version")
            digest = installer.get("digest")
            if digest and digest != "sha256:" + value["sha256"]: raise ValueError("asset-digest")
            return dict(value, checkedAt=now_text())
        location = response.headers.get("Location", "")
        parsed = urlsplit(location)
        if (parsed.scheme != "https" or parsed.hostname != "release-assets.githubusercontent.com"
                or parsed.username or parsed.fragment or parsed.port not in (None, 443)):
            raise ValueError("redirect")
        return {"redirect": location}
    data = get(f"https://api.github.com/repos/{repository}/releases/assets/{asset_id}", "读取 Windows 更新信息", decode,
               headers={"Accept": "application/octet-stream"}, accept_redirect=True)
    if "redirect" in data: data = get(data["redirect"], "读取 Windows 更新信息", decode)
    return data
