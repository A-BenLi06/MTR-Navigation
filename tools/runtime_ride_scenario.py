"""Real singleplayer ride: query/select, native door boarding, riding, sneak alighting, transfer.

No tracker corrections, synthetic passengers, fabricated arrivals, or clock acceleration.
"""
import json
import sys
import time
from java_mc_cli import call, OUTPUT


def prepare():
    call('resume');call('navigate')
    for _ in range(30):
        s=call()
        if s.get('catalog'):break
        time.sleep(1)
    stations=s['catalog']['stations']
    assert len(stations)==4 and all(s['name'].startswith('QA ') for s in stations), 'Use only the generated QA world'
    call('cancel');call('command',command='tp Dev 30 -57 -4');call('command',command='forceload add 0 -16 780 32');time.sleep(3)
    for x,z in [(0,0),(150,0),(300,0),(300,20),(600,20)]:
        for z1,z2 in [(z-5,z-1),(z+1,z+5)]:
            call('command',command=f'fill {x} -59 {z1} {x+60} -59 {z2} mtr:platform')
    origin=next(s['id'] for s in stations if s['name'].startswith('QA Origin|'))
    destination=next(s['id'] for s in stations if s['name'].startswith('QA Destination|'))
    call('query',origin=origin,destination=destination)
    for _ in range(20):
        time.sleep(1);s=call()
        if s['journeys']:break
    assert s['journeys'] and len(s['journeys'][0]['legs'])==2, 'Expected a real two-train query result'
    call('select',index=0);call('resume');time.sleep(1)


def assert_opacity(state, completed):
    routes=[leg['routeId'] for leg in state['tracking']['journey']['legs']]
    for index, route in enumerate(routes):
        values={e['opacity'] for entries in state['renderPalette'].values() for e in entries if e['id']==route}
        assert values == ({.35} if index < completed else {1.0}), (route, completed, values)


def capture(name, state):
    (OUTPUT / (name + '.json')).write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')
    call('screenshot', name=name)


def capture_maps(prefix):
    for key, suffix, zoom in [('gui.xaero_open_map','xaero',-3),('key.journeymap.map_toggle_alt','journeymap',-1)]:
        call('key',name=key);time.sleep(.6);call('scroll',amount=zoom);call('hoverCorner');time.sleep(.3)
        s=call();assert 'GuiMap' in s['screen'] or 'Fullscreen' in s['screen'],s['screen']
        assert not s['paused'];capture(prefix+'-'+suffix,s);call('resume');time.sleep(.2)


def run():
    call('resume')
    (OUTPUT/'scenario-result.json').write_text(json.dumps({'passed':False,'running':True}),encoding='utf-8')
    milestones = []
    started = time.monotonic()
    last = None
    boarded = set()
    leaving = set()
    last_board_attempt = 0
    while time.monotonic() - started < 650:
        s = call()
        t = s.get('tracking')
        if not t:
            raise RuntimeError('Start a real queried journey before running this scenario')
        leg_index = t['legIndex']
        stage = (leg_index, t['phase'], t['notice'], t['reliable'])
        if stage != last:
            print(json.dumps({'elapsed': round(time.monotonic()-started, 1), 'stage': stage, 'map': s['mapStatus'], 'player': s.get('player')}, ensure_ascii=False), flush=True)
            last = stage
        if t['phase'] == 'COMPLETE':
            time.sleep(1)
            final=call();assert_opacity(final,len(t['journey']['legs']))
            assert final['uuid'] not in final['native']['snapshot']['riders']
            capture('complete-native-alighting', final)
            call('command',command='tp Dev 330 -57 30');time.sleep(.4);capture_maps('complete')
            milestones.append('COMPLETE')
            (OUTPUT/'scenario-result.json').write_text(json.dumps({'passed': True, 'milestones': milestones,'journeyId':t['journey']['id'],'durationSeconds':round(time.monotonic()-started,2)}, indent=2), encoding='utf-8')
            print('PASS real native two-train journey', flush=True)
            return
        leg = t['journey']['legs'][leg_index]
        if t['phase']=='RIDING':
            assert t['reliable'], ('Native ride unexpectedly lost identity',leg_index,t['notice'])
        if t['phase'] in ('WAITING','TRANSFER','WALKING'):
            eligible = [v for v in s['clientVehicles'] if v['siding'] == leg['run']['sidingId']
                        and v['route'] == leg['routeId'] and v['platform'] == leg['fromPlatformId'] and not v['moving'] and v['doors'] > 0]
            now = s.get('native', {}).get('sampleMillis', 0)
            if eligible and abs(now - leg['departure']) < 45000 and time.monotonic()-last_board_attempt > 3:
                v = eligible[0]
                print('ENTER REAL NATIVE DOOR', v['id'], 'leg', leg_index, flush=True)
                result = call('door', vehicle=v['id'], teleport=True)
                print(json.dumps({'doorPosition':result.get('teleport'),'doorCount':len(result.get('doorways',[]))}, ensure_ascii=False), flush=True)
                # A human walks through the doorway. MTR publishes its native passenger update
                # after movement input; teleporting and standing still does not send that update.
                yaw=0 if result['teleport']['z'] < v['head']['z'] else 180
                call('look',yaw=yaw,pitch=0);call('key',name='key.forward',ticks=4)
                last_board_attempt = time.monotonic()
        if t['phase'] == 'RIDING' and t['reliable'] and leg_index not in boarded:
            assert s['uuid'] in s['native']['snapshot']['riders']
            assert_opacity(s,leg_index)
            capture('boarded-native-leg-'+str(leg_index), s)
            milestones.append('RIDING_'+str(leg_index)); boarded.add(leg_index)
            print('PASS native boarding leg', leg_index, flush=True)
        if t['phase']=='RIDING' and leg_index==0 and t['reliable'] and 'MIDDLE' not in milestones:
            rider=s['native']['snapshot']['riders'].get(s['uuid'],{})
            if rider.get('atPlatform') and rider.get('stopIndex')==1:
                assert_opacity(s,0);capture('intermediate-native-stop',s);milestones.append('MIDDLE')
                print('PASS ordinary intermediate stop retains current ride',flush=True)
        if t['phase'] == 'RIDING' and t['notice'] == 'alight_now' and leg_index not in leaving:
            capture('arrived-native-leg-'+str(leg_index), s)
            print('ALIGHT BY REAL SNEAK INPUT', leg_index, flush=True)
            call('key', name='key.sneak', ticks=45)
            time.sleep(2.6)
            platform=next(p for p in s['catalog']['platforms'] if p['id'] == leg['toPlatformId'])
            call('command', command=f"tp Dev {platform['x']} -57 {platform['z']-4}")
            leaving.add(leg_index)
            milestones.append('ALIGHT_'+str(leg_index))
        if t['phase'] == 'WALKING' and 'WALKING_'+str(leg_index) not in milestones:
            assert_opacity(s,leg_index)
            capture('transfer-after-native-alighting', s)
            milestones.append('WALKING_'+str(leg_index))
            capture_maps('transfer')
            # Move to the second platform's vicinity; native door intersection still owns boarding.
            p=next(p for p in s['catalog']['platforms'] if p['id']==leg['fromPlatformId'])
            call('command',command=f"tp Dev {p['x']} -57 {p['z']-4}")
        time.sleep(1)
    raise TimeoutError('Real journey did not complete in the test window')


if __name__ == '__main__':
    try:
        if '--prepare' in sys.argv:prepare()
        run()
    except Exception as error:
        (OUTPUT/'scenario-result.json').write_text(json.dumps({'passed':False,'error':str(error)},indent=2),encoding='utf-8')
        raise
