"""Explicit Windows-only release lookup. Never downloads or executes installers."""
import json
import re
from urllib.parse import urlsplit
from . import __version__

REPOSITORY = "JieShenAI/OUNet"


def validate_metadata(data, repository=REPOSITORY):
    if not isinstance(data, dict) or data.get("platform")!="windows-x64":raise ValueError("platform")
    version=str(data.get("version",""))
    if not re.fullmatch(r"\d+\.\d+\.\d+",version):raise ValueError("version")
    url=str(data.get("url",""));tag="windows-v"+version
    expected=f"https://github.com/{repository}/releases/download/{tag}/OUNet-{version}-Setup-x64.exe"
    if not repository or url!=expected:raise ValueError("source")
    sha=str(data.get("sha256",""))
    if not re.fullmatch(r"[0-9a-fA-F]{64}",sha):raise ValueError("hash")
    newer=tuple(map(int,version.split('.')))>tuple(map(int,__version__.split('.')))
    return {"version":version,"url":url,"sha256":sha.lower(),"newer":newer,
            "notes":str(data.get("notes",""))[:2000],"message":"发现可用 Windows 版本。" if newer else "当前已是此发布源的最新 Windows 版本。"}


def check_update(client, repository=REPOSITORY):
    if not repository:return {"message":"Windows 更新源尚未配置。"}
    def release_decode(text,response):
        data=json.loads(text)
        if not isinstance(data,list):raise ValueError("releases")
        return data
    releases=client.get(f"https://api.github.com/repos/{repository}/releases?per_page=30", "检查 Windows 发布", release_decode)
    valid=[r for r in releases if isinstance(r,dict) and not r.get("draft") and not r.get("prerelease")
           and re.fullmatch(r"windows-v\d+\.\d+\.\d+",str(r.get("tag_name","")))]
    if not valid:return {"message":"发布源尚未提供 Windows 正式版本。"}
    release=max(valid,key=lambda r:tuple(map(int,r["tag_name"][9:].split('.'))))
    asset=next((a for a in release.get("assets",[]) if isinstance(a,dict) and a.get("name")=="windows-update.json"),None)
    if not asset:return {"message":"该 Windows 发布缺少更新元数据。"}
    # API asset endpoint avoids following browser-download redirects to arbitrary hosts.
    asset_id=asset.get("id")
    if type(asset_id) is not int or asset_id<=0:raise ValueError("asset")
    # GitHub API raw asset content is requested explicitly; no credentials included.
    def metadata_decode(text,response):
        if response.status_code==200:
            value=validate_metadata(json.loads(text),repository)
            if value["version"]!=release["tag_name"][9:]:raise ValueError("release-version")
            return value
        location=response.headers.get("Location","")
        parsed=urlsplit(location)
        if parsed.scheme!="https" or parsed.hostname!="release-assets.githubusercontent.com" or parsed.username or parsed.port not in (None,443):
            raise ValueError("redirect")
        return {"redirect":location}
    data=client.get(f"https://api.github.com/repos/{repository}/releases/assets/{asset_id}",
                    "读取 Windows 更新信息", metadata_decode, headers={"Accept":"application/octet-stream"},accept_redirect=True)
    if "redirect" in data:
        return client.get(data["redirect"],"读取 Windows 更新信息",metadata_decode)
    return data
