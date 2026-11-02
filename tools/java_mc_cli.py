"""Control only the opt-in localhost Java singleplayer QA client and preserve raw evidence."""
import json
import pathlib
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding='utf-8')

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/runtime-ride'
BASE = 'http://localhost:18761'
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(op='state', **fields):
    payload = None if op == 'state' else json.dumps({'op': op, **fields}).encode()
    req = urllib.request.Request(BASE + ('/state' if payload is None else '/action'), data=payload,
                                 headers={'Content-Type': 'application/json'})
    with LOCAL_HTTP.open(req, timeout=20) as response:
        result = json.load(response)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with (OUTPUT / 'cli.jsonl').open('a', encoding='utf-8') as log:
        log.write(json.dumps({'at': time.time(), 'op': op, 'fields': fields, 'result': result}, ensure_ascii=False) + '\n')
    if op == 'state':
        (OUTPUT / 'latest-state.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return result


def summary(state):
    tracking = state.get('tracking')
    native = state.get('native', {})
    snapshot = native.get('snapshot', {})
    palettes = {}
    for entries in state.get('renderPalette', {}).values():
        for entry in entries:
            palettes.setdefault(entry.get('id'), set()).add(entry.get('opacity'))
    return {'connected': state.get('connected'), 'screen': state.get('screen'), 'paused': state.get('paused'),
            'player': state.get('player'), 'status': state.get('status'), 'map': state.get('mapStatus'),
            'tracking': None if tracking is None else {k: tracking.get(k) for k in ('legIndex', 'phase', 'notice', 'reliable')},
            'journeys': len(state.get('journeys', [])), 'events': len(snapshot.get('events', [])),
            'nativeRiders': snapshot.get('riders'), 'nativeVehicles': native.get('vehicles'),
            'clientVehicles': state.get('clientVehicles'),
            'opacityByRoute': {str(k): sorted(v) for k, v in palettes.items()}, 'error': native.get('error') or state.get('overlayError')}


if __name__ == '__main__':
    op = sys.argv[1] if len(sys.argv) > 1 else 'state'
    values = {}
    for argument in sys.argv[2:]:
        key, value = argument.split('=', 1)
        try:
            values[key] = json.loads(value)
        except json.JSONDecodeError:
            values[key] = value
    result = call(op, **values)
    print(json.dumps(summary(result) if op == 'state' else result, ensure_ascii=False, indent=2))
