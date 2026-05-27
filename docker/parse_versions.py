#!/usr/bin/env python3
"""Parse docker/versions.yml into a build matrix.

Usage:
    python3 docker/parse_versions.py [versions_file]          # TSV output (default)
    python3 docker/parse_versions.py --format json [versions_file]

Output formats:
    tsv  (default) — tab-separated lines: metabase_version<TAB>driver_version
    json — GitHub Actions matrix; writes to GITHUB_OUTPUT when that env var is
            set, otherwise prints JSON to stdout
"""

import argparse
import functools
import json
import os
import re
import ssl
import sys
import certifi
import yaml
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

_SSL_CTX = ssl.create_default_context(cafile=certifi.where())
_RELEASES_BASE = 'https://github.com/motherduckdb/metabase_duckdb_driver/releases/download'


@functools.lru_cache(maxsize=None)
def jar_exists(driver_version):
    url = f'{_RELEASES_BASE}/{driver_version}/duckdb.metabase-driver.jar'
    try:
        with urlopen(Request(url, method='HEAD'), timeout=15, context=_SSL_CTX) as resp:
            return resp.status == 200
    except (HTTPError, URLError):
        return False


def is_series(key):
    return bool(re.match(r'^v\d+\.\d+$', key))


def version_key(v):
    return tuple(int(x) for x in v.lstrip('v').split('.'))


def fetch_patch_versions(series):
    tags = set()
    url = (
        'https://hub.docker.com/v2/repositories/metabase/metabase/tags'
        '?name=' + series + '.&page_size=100'
    )
    pattern = re.compile(r'^' + re.escape(series) + r'\.\d+$')
    while url:
        try:
            with urlopen(url, timeout=30, context=_SSL_CTX) as resp:
                data = json.loads(resp.read().decode())
        except (HTTPError, URLError) as e:
            print(f'Error fetching Docker Hub tags for series {series}: {e}', file=sys.stderr)
            sys.exit(1)
        for result in data.get('results', []):
            name = result['name']
            if pattern.match(name):
                tags.add(name)
        url = data.get('next')
    return sorted(tags)


def load_combinations(versions_file):
    with open(versions_file) as f:
        data = yaml.safe_load(f)

    specific_versions = {key for key in data if not is_series(key)}
    combinations = []

    for key, drivers in data.items():
        if is_series(key):
            all_versions = fetch_patch_versions(key)
            if not all_versions:
                print(f'Warning: no Docker Hub tags found for series {key}', file=sys.stderr)
            versions = [v for v in all_versions if v not in specific_versions]
        else:
            versions = [key]
        available_drivers = []
        for driver in drivers:
            if jar_exists(driver):
                available_drivers.append(driver)
            else:
                print(f'Warning: JAR not found for driver {driver}, skipping', file=sys.stderr)
        for version in versions:
            for driver in available_drivers:
                combinations.append((version, driver))

    combinations.sort(key=lambda c: (version_key(c[0]), version_key(c[1])))
    return combinations


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('versions_file', nargs='?', default='docker/versions.yml')
    parser.add_argument('--format', choices=['tsv', 'json'], default='tsv')
    args = parser.parse_args()

    combinations = load_combinations(args.versions_file)

    if args.format == 'tsv':
        for version, driver in combinations:
            print(version, driver, sep='\t')
    else:
        matrix_items = [
            {
                'metabase_version': version.lstrip('v'),
                'driver_version': driver,
                'tag': f'{version.lstrip("v")}-duckdb{driver}',
            }
            for version, driver in combinations
        ]
        latest_tag = matrix_items[-1]['tag'] if matrix_items else ''

        github_output = os.environ.get('GITHUB_OUTPUT')
        if github_output:
            with open(github_output, 'a') as out:
                out.write(f'matrix={json.dumps({"include": matrix_items})}\n')
                out.write(f'latest_tag={latest_tag}\n')
        else:
            print(json.dumps({'matrix': {'include': matrix_items}, 'latest_tag': latest_tag}, indent=2))


if __name__ == '__main__':
    main()
