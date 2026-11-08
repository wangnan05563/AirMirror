"""Publish a GitHub Release without gh CLI.

Uses only the standard library. Token comes from GITHUB_TOKEN or GH_TOKEN;
it is never written to disk or echoed.

Sequence (draft-first, so a public release never appears half-uploaded):
  1. create refs/tags/<tag> at <sha>
  2. create a draft release for that tag
  3. upload every asset
  4. flip the draft to published
On any failure the remote tag and draft release created by this run are deleted.

Exit codes: 0 success, 1 failure, 2 bad usage.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = 'https://api.github.com'
UPLOADS = 'https://uploads.github.com'
RETRIES = 3


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__('HTTP %s: %s' % (status, message))
        self.status = status


def token():
    value = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
    if not value or not value.strip():
        raise SystemExit('missing GITHUB_TOKEN / GH_TOKEN')
    return value.strip()


def request(method, url, tok, payload=None, raw=None, timeout=60):
    headers = {
        'Authorization': 'Bearer ' + tok,
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'airmirror-publish-release',
    }
    data = None
    if raw is not None:
        data = raw
        headers['Content-Type'] = 'application/octet-stream'
    elif payload is not None:
        data = json.dumps(payload).encode('utf-8')
        headers['Content-Type'] = 'application/json'

    last = None
    for attempt in range(1, RETRIES + 1):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read()
                return resp.status, (json.loads(body) if body else None)
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode('utf-8', 'replace')
            try:
                detail = json.loads(detail).get('message', detail)
            except ValueError:
                pass
            # 5xx is worth retrying; 4xx is a real answer (auth, already exists, ...).
            if exc.code >= 500 and attempt < RETRIES:
                last = exc
                time.sleep(attempt * 2)
                continue
            raise ApiError(exc.code, detail)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last = exc
            if attempt < RETRIES:
                time.sleep(attempt * 2)
                continue
            raise ApiError('network', str(exc))
    raise ApiError('network', str(last))


def try_get(url, tok):
    try:
        return request('GET', url, tok)[1]
    except ApiError as exc:
        if exc.status == 404:
            return None
        raise


def repo_from_remote(url):
    match = re.search(r'github\.com[:/]+([^/]+)/([^/]+?)(?:\.git)?/*$', url.strip())
    if not match:
        raise SystemExit('cannot parse GitHub repo from origin: %s' % url)
    return '%s/%s' % (match.group(1), match.group(2))


def main():
    parser = argparse.ArgumentParser(description='Publish a GitHub Release without gh.')
    parser.add_argument('--repo', help='owner/name (default: from git remote origin)')
    parser.add_argument('--remote', default='origin')
    parser.add_argument('--tag', required=True)
    parser.add_argument('--sha', help='commit the tag must point at (required when creating the tag)')
    parser.add_argument('--title', required=True)
    parser.add_argument('--notes', default='')
    parser.add_argument('--notes-file')
    parser.add_argument('assets', nargs='*')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--verify-upload', action='store_true',
                        help='create a draft, upload, verify remote sizes, then delete the draft and tag')
    args = parser.parse_args()

    repo = args.repo
    if not repo:
        import subprocess
        url = subprocess.check_output(['git', 'remote', 'get-url', args.remote], text=True)
        repo = repo_from_remote(url)

    notes = args.notes
    if args.notes_file:
        raw = open(args.notes_file, 'rb').read()
        for enc in ('utf-8-sig', 'gbk', 'utf-8'):
            try:
                notes = raw.decode(enc)
                break
            except UnicodeDecodeError:
                continue

    tok = token()
    created_ref = False
    release_id = None

    try:
        status, info = request('GET', '%s/repos/%s' % (API, repo), tok)
        perms = info.get('permissions') or {}
        if not perms.get('push'):
            raise SystemExit('token has no push permission on %s' % repo)
        print('repo: %s (%s)' % (repo, 'private' if info.get('private') else 'public'))

        existing_ref = try_get('%s/repos/%s/git/ref/tags/%s' % (API, repo, urllib.parse.quote(args.tag)), tok)
        existing_release = try_get('%s/repos/%s/releases/tags/%s' % (API, repo, urllib.parse.quote(args.tag)), tok)

        if existing_release:
            raise SystemExit('release %s already exists: %s' % (args.tag, existing_release.get('html_url')))
        if existing_ref:
            raise SystemExit('tag %s already exists on the remote' % args.tag)
        if not args.sha:
            raise SystemExit('--sha is required: the tag does not exist yet')

        missing = [p for p in args.assets if not os.path.isfile(p)]
        if missing:
            raise SystemExit('asset not found: %s' % ', '.join(missing))

        print('tag: %s -> %s' % (args.tag, args.sha))
        print('assets: %d' % len(args.assets))
        if args.dry_run:
            print('DRY RUN: no remote writes performed')
            return 0

        # 先置位再写：POST 超时/5xx 时远端可能其实已建成，回滚必须覆盖这种不确定态。
        created_ref = True
        request('POST', '%s/repos/%s/git/refs' % (API, repo), tok,
                payload={'ref': 'refs/tags/' + args.tag, 'sha': args.sha})
        print('remote tag created')

        status, release = request('POST', '%s/repos/%s/releases' % (API, repo), tok, payload={
            'tag_name': args.tag,
            'name': args.title,
            'body': notes,
            'draft': True,
        })
        release_id = release['id']
        upload_base = re.sub(r'\{.*\}$', '', release['upload_url'])

        for path in args.assets:
            name = os.path.basename(path)
            size = os.path.getsize(path)
            print('uploading %s (%s bytes)' % (name, format(size, ',')))
            with open(path, 'rb') as handle:
                status, asset = request('POST', '%s?name=%s' % (upload_base, urllib.parse.quote(name)),
                                        tok, raw=handle.read(), timeout=300)
            print('  ok id=%s' % asset.get('id'))

        if args.verify_upload:
            status, published = request('GET', '%s/repos/%s/releases/%s' % (API, repo, release_id), tok)
            remote = {item['name']: item['size'] for item in published.get('assets') or []}
            for path in args.assets:
                name = os.path.basename(path)
                local = os.path.getsize(path)
                if remote.get(name) != local:
                    raise ApiError('verify', 'asset %s size mismatch: remote=%s local=%s'
                                   % (name, remote.get(name), local))
            request('DELETE', '%s/repos/%s/releases/%s' % (API, repo, release_id), tok)
            release_id = None
            request('DELETE', '%s/repos/%s/git/refs/tags/%s' % (API, repo, urllib.parse.quote(args.tag)), tok)
            created_ref = False
            print('VERIFY OK: %d asset(s) uploaded with matching sizes; draft and tag removed' % len(args.assets))
            return 0

        request('PATCH', '%s/repos/%s/releases/%s' % (API, repo, release_id), tok,
                payload={'draft': False})
        print('RELEASE_URL=%s' % release.get('html_url', ''))
        return 0
    except BaseException as exc:
        message = str(exc)
        if not args.dry_run and (release_id or created_ref):
            print('publish failed: %s' % message, file=sys.stderr)
            if not release_id:
                # 创建请求可能已落库但没拿到响应；按 tag 找回本轮的草稿再删。
                try:
                    for rel in request('GET', '%s/repos/%s/releases?per_page=100' % (API, repo), tok)[1] or []:
                        if rel.get('tag_name') == args.tag:
                            release_id = rel['id']
                            break
                except Exception:
                    pass
            if release_id:
                try:
                    request('DELETE', '%s/repos/%s/releases/%s' % (API, repo, release_id), tok)
                    print('rolled back draft release', file=sys.stderr)
                except Exception as cleanup:
                    print('rollback release failed: %s' % cleanup, file=sys.stderr)
            if created_ref:
                try:
                    request('DELETE', '%s/repos/%s/git/refs/tags/%s' % (API, repo, urllib.parse.quote(args.tag)), tok)
                    print('rolled back remote tag', file=sys.stderr)
                except Exception as cleanup:
                    print('rollback tag failed: %s' % cleanup, file=sys.stderr)
        else:
            print('publish failed: %s' % message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
