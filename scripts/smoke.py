#!/usr/bin/env python3
"""Exercise the four running services through the gateway. No third-party packages."""
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import shlex
import time
import urllib.error
import urllib.request
import uuid

config = Path(__file__).resolve().parents[1] / '.env'
if config.exists():
    for raw in config.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.removeprefix('export ').partition('=')
        if separator:
            values = shlex.split(value, comments=True)
            os.environ.setdefault(key.strip(), values[0] if values else '')
BASE = os.getenv('API_BASE_URL', 'http://localhost:' + os.getenv('GATEWAY_PORT', '8080'))


def request(method, path, token=None, body=None, expected=(200,), trace=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if trace:
        headers['X-Trace-Id'] = trace
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    data = response.read()
    result = json.loads(data) if data else None
    assert response.code in expected, (method, path, response.code, result)
    assert response.headers.get('X-Trace-Id'), ('Missing trace id', path)
    if trace:
        assert response.headers['X-Trace-Id'] == trace
    return result, response.code


def wait_for_get(path, token, timeout=120):
    """Wait for discovery using reads only; business requests are never retried here."""
    deadline = time.monotonic() + timeout
    last_failure = None
    while True:
        try:
            result, status = request('GET', path, token, expected=(200, 502, 503, 504))
            if status == 200:
                assert isinstance(result, dict) and isinstance(result.get('content'), list), (
                    'Invalid readiness response', path, result)
                return result
            last_failure = (status, result)
        except OSError as error:
            last_failure = str(error)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise AssertionError(('Service chain did not become ready', path, last_failure))
        time.sleep(min(1, remaining))


def wait_for_services(token):
    # Gateway and Booking have independent discovery caches. Check both paths
    # before creating fixtures or attempting a state-changing booking request.
    wait_for_get('/api/hotels', token)
    start = dt.date.today() + dt.timedelta(days=30)
    end = start + dt.timedelta(days=1)
    wait_for_get('/api/bookings/recommendations?hotelId=1&startDate=' + str(start) +
                 '&endDate=' + str(end), token)
    print('READY: Gateway -> Hotel and Gateway -> Booking -> Hotel.', flush=True)


def main():
    # Eureka clients fetch registry asynchronously; wait for an actual routed call.
    for attempt in range(90):
        try:
            admin, _ = request('POST', '/api/auth/login', body={
                'username': 'admin', 'password': os.getenv('DEMO_ADMIN_PASSWORD', 'admin12345')})
            break
        except (AssertionError, OSError):
            if attempt == 89:
                raise
            time.sleep(1)
    admin_token = admin['accessToken']
    wait_for_services(admin_token)
    suffix = uuid.uuid4().hex[:10]
    user, _ = request('POST', '/api/auth/register', body={
        'username': 'smoke_' + suffix, 'password': 'SmokePass123!'}, expected=(200, 201))
    other, _ = request('POST', '/api/auth/register', body={
        'username': 'other_' + suffix, 'password': 'SmokePass123!'}, expected=(200, 201))
    token = user['accessToken']
    request('GET', '/api/hotels', expected=(401,))
    request('GET', '/api/hotels', token='invalid', expected=(401,))
    request('POST', '/api/hotels', token, {}, expected=(403,))
    request('POST', '/api/rooms/allocate', admin_token, {}, expected=(403,))
    request('POST', '/api/rooms/1/release', token, {}, expected=(403,))
    hotel, _ = request('POST', '/api/hotels', admin_token,
                       {'name': 'Smoke ' + suffix, 'city': 'Kazan', 'address': 'Test Street 1'}, (200, 201))
    rooms = []
    for number in ('101', '102', '103'):
        room, _ = request('POST', '/api/rooms', admin_token, {
            'hotelId': hotel['id'], 'number': number, 'available': True,
            'pricePerNight': 4500, 'capacity': 2}, (200, 201))
        rooms.append(room)
    start = dt.date.today() + dt.timedelta(days=30)
    end = start + dt.timedelta(days=2)
    payload = {'requestId': str(uuid.uuid4()), 'hotelId': hotel['id'], 'roomId': rooms[0]['id'],
               'autoSelect': False, 'startDate': str(start), 'endDate': str(end)}
    invalid = dict(payload, requestId=str(uuid.uuid4()), endDate=str(start))
    request('POST', '/api/bookings', token, invalid, (400,))
    booking, _ = request('POST', '/api/bookings', token, payload, (201,), trace='smoke-' + suffix)
    assert booking['status'] == 'CONFIRMED'
    replay, _ = request('POST', '/api/bookings', token, payload, (200,))
    assert replay['id'] == booking['id']
    request('POST', '/api/bookings', token, dict(payload, endDate=str(end + dt.timedelta(days=1))), (409,))
    request('GET', '/api/bookings/' + booking['id'], other['accessToken'], expected=(403, 404))
    request('DELETE', '/api/bookings/' + booking['id'], other['accessToken'], expected=(403, 404))
    history, _ = request('GET', '/api/bookings?page=0&size=2', token)
    assert all(row['userId'] == user['user']['id'] for row in history['content'])
    recommendations, _ = request('GET', '/api/bookings/recommendations?hotelId=' + str(hotel['id']) +
                                  '&startDate=' + str(start) + '&endDate=' + str(end), token)
    assert rooms[0]['id'] not in [room['id'] for room in recommendations['content']]
    canceled, _ = request('DELETE', '/api/bookings/' + booking['id'], token)
    assert canceled['status'] == 'CANCELLED' and not canceled['compensationRequired']
    request('DELETE', '/api/bookings/' + booking['id'], token)

    # Twelve different idempotency keys race for the exact same room and interval.
    def contender(_):
        return request('POST', '/api/bookings', token,
                       dict(payload, requestId=str(uuid.uuid4())), (201, 409))
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(contender, range(12)))
    winners = [value for value, status in results if status == 201]
    assert len(winners) == 1, results
    request('DELETE', '/api/bookings/' + winners[0]['id'], token)

    # Non-overlapping stays force the least-used algorithm to distribute allocations.
    for offset in range(12):
        arrival = end + dt.timedelta(days=offset * 2)
        request('POST', '/api/bookings', token, dict(payload, requestId=str(uuid.uuid4()),
                autoSelect=True, roomId=999999, startDate=str(arrival),
                endDate=str(arrival + dt.timedelta(days=1))), (201,))
    available, _ = request('GET', '/api/rooms/recommend?hotelId=' + str(hotel['id']) + '&startDate=' + str(end + dt.timedelta(days=40)) + '&endDate=' + str(end + dt.timedelta(days=41)), token)
    counts = [row['timesBooked'] for row in available['content']]
    assert max(counts) - min(counts) <= 1, counts
    assert [(r['timesBooked'], r['id']) for r in available['content']] == sorted(
        (r['timesBooked'], r['id']) for r in available['content'])
    stats, _ = request('GET', '/api/hotels/' + str(hotel['id']) + '/statistics?startDate=' +
                       str(start) + '&endDate=' + str(end + dt.timedelta(days=30)), admin_token)
    assert stats['bookedNights'] == 12, stats
    print('PASS: JWT, roles, trace, CRUD, validation, ownership, idempotency, compensation, '
          '12-way concurrency, recommendations, balanced allocation and occupancy statistics.')


if __name__ == '__main__':
    main()
