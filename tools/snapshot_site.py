#!/usr/bin/env python3
"""Stream publicly linked website resources into Android assets, without a site-size cap."""
import hashlib
import html
import ipaddress
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import socket
import sys
import time
from collections import deque
from functools import lru_cache
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit, urldefrag
from urllib.request import Request, HTTPRedirectHandler, build_opener

ROOT = Path('app/src/main/assets/site')
DOLLAR_BRACE = chr(36) + chr(123)
STARTED = time.monotonic()
TIME_BUDGET = int(os.environ.get('SNAPSHOT_TIMEOUT_SECONDS', '14400'))
RESERVE = 4 * 1024 ** 3
queue = deque()
seen = set()
mapping = {}
failures = []
origins = set()
bytes_saved = 0
pages_saved = 0
files_saved = 0


def canonical(raw, base=''):
    value = urldefrag(urljoin(base, html.unescape(raw.strip())))[0]
    parsed = urlsplit(value)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname:
        return None
    if parsed.username or parsed.password:
        return None
    if not parsed.path:
        value = value.replace(parsed.netloc, parsed.netloc + '/', 1)
    return value


@lru_cache(maxsize=2048)
def public_host(host):
    addresses = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(info[4][0]).is_global for info in addresses):
        raise ValueError('Private or local network targets are not supported')


def validate_url(url):
    parsed = urlsplit(url)
    if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password:
        raise ValueError('Only public HTTP(S) URLs are supported')
    public_host(parsed.hostname)


class PublicRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


opener = build_opener(PublicRedirects())


def add(raw, base, document=False):
    if not raw or raw.strip().startswith(('#', 'data:', 'javascript:', 'mailto:', 'tel:', 'blob:')):
        return
    if DOLLAR_BRACE in raw or '$(' in raw:
        return
    url = canonical(raw, base)
    if not url:
        return
    if DOLLAR_BRACE in url:
        return
    if document and urlsplit(url).netloc.lower() not in origins:
        return
    if url not in seen:
        seen.add(url)
        queue.append(url)


def css_refs(text, base):
    for match in re.finditer(r'url\(\s*[\x22\x27]?([^\x22\x27)]+)', text, re.I):
        add(match.group(1).strip(), base)
    for match in re.finditer(r'@import\s+[\x22\x27]([^\x22\x27]+)', text, re.I):
        add(match.group(1), base)


def js_refs(text, base):
    for match in re.finditer(r'(?:\bfrom\s*|\bimport\s*\(?\s*)[\x22\x27]([^\x22\x27]+)', text):
        ref = match.group(1)
        if ref.startswith(('.', '/', 'http:','https:')):
            add(ref, base)
    for match in re.finditer(r'[\x22\x27\x60]([^\x22\x27\x60\s]+\.(?:css|m?js|json|wasm|png|jpe?g|gif|svg|webp|avif|ico|woff2?|ttf|otf|mp[34]|ogg|webm|wav|pdf|bin|data|zip)(?:\?[^\x22\x27\x60\s]*)?)[\x22\x27\x60]', text, re.I):
        add(match.group(1).replace('\\/', '/'), base)
    for match in re.finditer(r'\bfetch\(\s*[\x22\x27]([^\x22\x27]+)[\x22\x27]\s*\)', text):
        add(match.group(1), base)


class References(HTMLParser):
    def __init__(self, base):
        super().__init__(convert_charrefs=True)
        self.base = base
        self.script = False
        self.style = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'base' and attrs.get('href'):
            self.base = urljoin(self.base, attrs['href'])
        if tag in ('a', 'area', 'iframe'):
            add(attrs.get('href') or attrs.get('src'), self.base, document=True)
        elif tag == 'link':
            rel = set((attrs.get('rel') or '').lower().split())
            add(attrs.get('href'), self.base, document=not bool(rel & {'stylesheet', 'icon', 'preload', 'modulepreload', 'manifest', 'apple-touch-icon'}))
        elif tag not in ('form', 'base'):
            for name in ('src', 'poster', 'data-src'):
                add(attrs.get(name), self.base)
        for part in (attrs.get('srcset') or '').split(','):
            tokens = part.strip().split()
            if tokens:
                add(tokens[0], self.base)
        css_refs(attrs.get('style') or '', self.base)
        self.script = self.script or tag == 'script'
        self.style = self.style or tag == 'style'

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_endtag(self, tag):
        if tag == 'script':
            self.script = False
        if tag == 'style':
            self.style = False

    def handle_data(self, data):
        if self.script:
            js_refs(data, self.base)
        if self.style:
            css_refs(data, self.base)


def check_capacity():
    if time.monotonic() - STARTED > TIME_BUDGET:
        raise RuntimeError('Build download time exhausted; the snapshot is incomplete')
    if shutil.disk_usage(ROOT).free < RESERVE:
        raise RuntimeError('Insufficient build disk space; the snapshot is incomplete')


def download(url, entry):
    global bytes_saved, pages_saved, files_saved
    check_capacity()
    validate_url(url)
    req = Request(url, headers={'User-Agent': 'APK-Studio-Offline/1.0', 'Accept-Encoding': 'identity'})
    with opener.open(req, timeout=45) as response:
        final = canonical(response.geturl())
        validate_url(final)
        if url == entry:
            origins.add(urlsplit(final).netloc.lower())
        mime = response.headers.get_content_type()
        charset = response.headers.get_content_charset() or 'utf-8'
        if mime == 'application/octet-stream':
            mime = mimetypes.guess_type(final)[0] or mime
        suffix = Path(urlsplit(final).path).suffix
        if not re.fullmatch(r'\.[A-Za-z0-9]{1,10}', suffix):
            suffix = mimetypes.guess_extension(mime) or '.dat'
        relative = 'index.html' if url == entry else 'assets/' + hashlib.sha256(url.encode()).hexdigest() + suffix
        target = ROOT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        partial = target.with_suffix(target.suffix + '.part')
        try:
            with partial.open('wb') as output:
                while True:
                    check_capacity()
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
                    bytes_saved += len(chunk)
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    record = {'path': relative, 'mime': mime}
    mapping[url] = record
    mapping[final] = record
    seen.add(final)
    files_saved += 1
    if mime in ('text/html', 'application/xhtml+xml'):
        pages_saved += 1
        References(final).feed(target.read_text(encoding=charset, errors='replace'))
    elif mime == 'text/css':
        css_refs(target.read_text(encoding=charset, errors='replace'), final)
    elif mime in ('text/javascript', 'application/javascript', 'application/x-javascript'):
        js_refs(target.read_text(encoding=charset, errors='replace'), final)
    print('Saved', files_saved, 'files;', round(bytes_saved / 1024 ** 2, 1), 'MB;', len(queue), 'pending', flush=True)


def report(complete):
    result = {'complete': complete, 'coverage': 'publicly linked resources only', 'pages': pages_saved, 'files': files_saved, 'bytes': bytes_saved, 'pending': len(queue), 'failures': failures}
    Path('snapshot-report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return result


def main():
    config = json.loads(Path('apk-studio-snapshot.json').read_text(encoding='utf-8'))
    if not config.get('enabled'):
        print('Large-site snapshot is disabled; keeping any bundled snapshot.')
        return
    entry = canonical(config['entry'])
    if not entry:
        raise ValueError('Invalid website URL')
    validate_url(entry)
    origins.add(urlsplit(entry).netloc.lower())
    shutil.rmtree(ROOT, ignore_errors=True)
    ROOT.mkdir(parents=True)
    add(entry, entry)
    while queue:
        url = queue.popleft()
        if url in mapping:
            continue
        try:
            download(url, entry)
        except RuntimeError as error:
            failures.append({'url': url, 'error': str(error)})
            report(False)
            raise
        except Exception as error:
            failures.append({'url': url, 'error': str(error)})
            print('Could not download:', url, str(error), file=sys.stderr)
    result = report(not failures)
    if entry not in mapping:
        raise RuntimeError('Could not download the entry page; APK build stopped. See snapshot-report.json.')
    if failures:
        print('Warning:', len(failures), 'non-critical resources were not downloaded; the app will fetch them at runtime. See snapshot-report.json.')
    (ROOT / 'manifest.json').write_text(json.dumps({'entry': entry, 'files': mapping}, ensure_ascii=False), encoding='utf-8')
    print('Snapshot finished:', json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
