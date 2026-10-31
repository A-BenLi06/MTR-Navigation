"""Extract the compile-only public API from a pinned, verified Overlay release."""
import hashlib
import pathlib
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
NAME = 'CRTools-MTR-Map-Overlay-1.5.3-MC1.21.1-MTR4.1.0-beta.2-neoforge.jar'
SHA256 = 'bd959645ab86317197aa78ddbe79c8d3c58f510c102920ca054b809cfbc42a76'


def download():
    cache = ROOT / '.cache' / 'downloads'
    cache.mkdir(parents=True, exist_ok=True)
    source = cache / NAME
    if not source.exists() or hashlib.sha256(source.read_bytes()).hexdigest() != SHA256:
        request = urllib.request.Request(
            'https://github.com/teamCreating/MTR-Map-Overlay/releases/download/v1.5.3/' + NAME,
            headers={'User-Agent': 'MTR-Navigation-development'})
        with urllib.request.urlopen(request, timeout=90) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != SHA256:
            raise RuntimeError('Overlay release SHA256 mismatch')
        source.write_bytes(data)
    target = cache / 'mtrmap-api-1.0.0.jar'
    with zipfile.ZipFile(source) as release, zipfile.ZipFile(target, 'w') as api:
        entries = sorted(n for n in release.namelist()
                         if n.startswith('io/github/teamcreating/mtrmap/api/') and n.endswith('.class'))
        if not entries:
            raise RuntimeError('Overlay release has no public API')
        for name in entries:
            api.writestr(name, release.read(name))
        api.writestr('LICENSE', release.read('LICENSE'))
    print(target.name, 'extracted from verified Overlay 1.5.3', flush=True)


if __name__ == '__main__':
    download()
