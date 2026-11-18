#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${MJWATCH_SIGNING_KEY:?Set a reviewed release signing key path; no signing bypass is available}"
: "${TORRSERVER_ARTIFACT_DIR:?Set the directory containing pinned TorrServer-linux-amd64 and TorrServer-linux-arm64}"
MJWATCH_SERVER_VERSION=${MJWATCH_SERVER_VERSION:-0.1.6}
export MJWATCH_SERVER_VERSION
mkdir -p dist/server core/src/main/assets/server
for arch in amd64 arm64; do
 (cd server && CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build -trimpath -buildvcs=false -ldflags="-s -w -X dev.mjwatch/server/internal/service.Version=$MJWATCH_SERVER_VERSION" -o "../dist/server/mjwatch-linux-$arch" ./cmd/mjwatch)
done
python3 - <<'BUILD'
import hashlib,json,os,pathlib,shutil,subprocess
root=pathlib.Path.cwd();assets=root/'core/src/main/assets/server';lock=json.loads((root/'server/supply.lock.json').read_text());files=[]
for arch,digest in lock['artifacts'].items():
 source=pathlib.Path(os.environ['TORRSERVER_ARTIFACT_DIR'])/('TorrServer-linux-'+arch)
 if hashlib.sha256(source.read_bytes()).hexdigest()!=digest:raise SystemExit('TorrServer digest mismatch: '+arch)
 folder=assets/('linux-'+arch);folder.mkdir(exist_ok=True)
 shutil.copyfile(source,folder/'TorrServer');shutil.copyfile(root/'dist/server'/('mjwatch-linux-'+arch),folder/'mjwatch')
for name in ['install.sh','install.py','maintenance.py','renew.py','operation.py','mjwatchctl']:shutil.copyfile(root/'server/packaging'/name,assets/name)
for path in sorted(assets.rglob('*')):
 if path.is_file() and path.name not in ['manifest.json','manifest.sig','release-public.pem']:files.append(dict(path=path.relative_to(assets).as_posix(),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),size=path.stat().st_size))
manifest=dict(version=os.environ['MJWATCH_SERVER_VERSION'],schemaVersion=1,apiVersion=1,minClientApiVersion=1,torrserverVersion=lock['torrserverVersion'],channel=os.environ.get('MJWATCH_SUPPLY_CHANNEL','local-test'),files=files)
(assets/'manifest.json').write_text(json.dumps(manifest,sort_keys=True,separators=(',',':')))
key=os.environ['MJWATCH_SIGNING_KEY']
subprocess.run(['openssl','pkey','-in',key,'-pubout','-out',str(assets/'release-public.pem')],check=True)
subprocess.run(['openssl','dgst','-sha256','-sign',key,'-out',str(assets/'manifest.sig'),str(assets/'manifest.json')],check=True)
subprocess.run(['openssl','dgst','-sha256','-verify',str(assets/'release-public.pem'),'-signature',str(assets/'manifest.sig'),str(assets/'manifest.json')],check=True)
import zipfile
with zipfile.ZipFile(root/'dist/server'/('mjwatch-'+manifest['version']+'-supply.zip'),'w',zipfile.ZIP_DEFLATED) as archive:
 for path in sorted(assets.rglob('*')):
  if path.is_file():
   info=zipfile.ZipInfo(path.relative_to(assets).as_posix(),(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644 << 16;archive.writestr(info,path.read_bytes())
import gzip,tarfile,io
with (root/'dist/server'/('mjwatch-'+manifest['version']+'-supply.tar.gz')).open('wb') as output:
 with gzip.GzipFile(filename='',mode='wb',fileobj=output,mtime=0) as compressed:
  with tarfile.open(fileobj=compressed,mode='w',format=tarfile.USTAR_FORMAT) as archive:
   for path in sorted(assets.rglob('*')):
    if path.is_file():
     data=path.read_bytes();info=tarfile.TarInfo(path.relative_to(assets).as_posix());info.size=len(data);info.mode=0o644;info.mtime=0;archive.addfile(info,io.BytesIO(data))
BUILD
(cd dist/server && shasum -a 256 mjwatch-linux-* mjwatch-*-supply.tar.gz mjwatch-*-supply.zip > SHA256SUMS)
