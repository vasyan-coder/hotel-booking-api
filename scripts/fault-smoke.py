#!/usr/bin/env python3
"""Real HTTP saga fault test. Requires Hotel Service and a packaged Booking jar.

Reads .env, starts an isolated Booking JVM plus an ephemeral forwarding proxy,
then always stops both. Uses only the Python standard library. Fixtures remain
in Hotel's in-memory audit history; each run creates a separate hotel.
"""
import datetime as dt
import json
import os
from pathlib import Path
import shlex
import shutil
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def environment():
    result = dict(os.environ)
    config = ROOT / '.env'
    if config.exists():
        for raw in config.read_text().splitlines():
            line = raw.strip()
            if not line or line.startswith('#'):
                continue
            key, separator, value = line.removeprefix('export ').partition('=')
            if separator:
                parsed = shlex.split(value, comments=True)
                result.setdefault(key.strip(), parsed[0] if parsed else '')
    if not result.get('JWT_SECRET'):
        raise RuntimeError('JWT_SECRET is missing. Run ./scripts/init-env.sh first.')
    return result


def request(base, method, path, token=None, body=None, expected=(200,), timeout=15):
    headers = {'Content-Type': 'application/json', 'X-Trace-Id': 'fault-smoke'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    call = urllib.request.Request(base + path, method=method, headers=headers,
                                  data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(call, timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        result = json.loads(raw) if raw else None
        assert response.status in expected, (method, path, response.status, result)
        return result


class FaultState:
    def __init__(self, hotel_url, log_path):
        self.hotel_url = hotel_url
        self.log_path = log_path
        self.lock = threading.Lock()
        self.hide_allocation_replies = True
        self.block_release = True
        self.transient_allocation_failures = 0
        self.allocation_attempts = 0
        self.allocation_commits = 0
        self.release_failures = 0
        self.release_successes = 0
        self.last_allocation = None
        self.internal_token = None

    def log(self, **event):
        with self.lock:
            with self.log_path.open('a') as stream:
                stream.write(json.dumps({'time': time.time(), **event}) + '\n')


class FaultProxy(BaseHTTPRequestHandler):
    # The server's worker threads are daemon threads; held replies do not block shutdown.
    def do_POST(self):
        state = self.server.fault_state
        body = self.read_request_body()
        allocation = self.path == '/api/rooms/allocate'
        release = self.path.endswith('/release')
        with state.lock:
            if allocation:
                state.allocation_attempts += 1
                state.last_allocation = json.loads(body)
                state.internal_token = self.headers.get('Authorization', '').removeprefix('Bearer ')
            hide_reply = allocation and state.hide_allocation_replies
            fail_allocation = allocation and state.transient_allocation_failures > 0
            if fail_allocation:
                state.transient_allocation_failures -= 1
            fail_release = release and state.block_release
            if fail_release:
                state.release_failures += 1
        if fail_release or fail_allocation:
            state.log(event='injected_503', path=self.path)
            self.respond(503, b'{"code":"INJECTED_UNAVAILABLE"}')
            return
        headers = {key: self.headers[key] for key in ('Content-Type', 'Authorization', 'X-Trace-Id')
                   if self.headers.get(key)}
        call = urllib.request.Request(state.hotel_url + self.path, method='POST', headers=headers, data=body)
        try:
            try:
                response = urllib.request.urlopen(call, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status, payload = response.status, response.read()
        except OSError as error:
            state.log(event='upstream_error', kind=type(error).__name__)
            self.respond(502, b'{"code":"UPSTREAM_UNAVAILABLE"}')
            return
        with state.lock:
            if allocation and status == 200:
                state.allocation_commits += 1
            if release and status == 204:
                state.release_successes += 1
        state.log(event='upstream_response', path=self.path, status=status, withheld=hide_reply)
        if hide_reply and status == 200:
            # Hotel has committed before this delay. Booking times out after 400ms.
            time.sleep(1.2)
            self.close_connection = True
            return
        self.respond(status, payload)

    def read_request_body(self):
        # Spring's streaming request factory uses HTTP/1.1 chunked transfer.
        if 'chunked' not in self.headers.get('Transfer-Encoding', '').lower():
            return self.rfile.read(int(self.headers.get('Content-Length', '0')))
        chunks = []
        while True:
            size_line = self.rfile.readline()
            size = int(size_line.split(b';', 1)[0].strip(), 16)
            if size == 0:
                while self.rfile.readline() not in (b'\r\n', b'\n', b''):
                    pass
                return b''.join(chunks)
            chunks.append(self.rfile.read(size))
            if self.rfile.read(2) != b'\r\n':
                raise ValueError('Malformed HTTP chunk terminator')

    def respond(self, status, payload):
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected when a deliberately delayed client has timed out.

    def log_message(self, format_string, *args):
        pass


def unused_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def wait_until(action, description, timeout=30):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            value = action()
            if value:
                return value
        except (AssertionError, OSError) as error:
            last_error = error
        time.sleep(0.2)
    raise AssertionError(f'Timed out: {description}; last error: {last_error}')


def run_checks(booking_url, hotel_url, env, state, suffix):
    # Health can become UP before ApplicationRunner commits seeded accounts.
    admin = wait_until(lambda: request(booking_url, 'POST', '/api/auth/login', body={
        'username': 'admin', 'password': env.get('DEMO_ADMIN_PASSWORD', 'admin12345')})['accessToken'],
        'seeded admin account')
    token = request(booking_url, 'POST', '/api/auth/register', body={
        'username': 'fault_' + suffix, 'password': 'FaultSmoke123!'}, expected=(200, 201))['accessToken']
    hotel = request(hotel_url, 'POST', '/api/hotels', admin, {
        'name': 'Fault smoke ' + suffix, 'city': 'Kazan', 'address': 'Fault Test 1'}, (201,))
    room = request(hotel_url, 'POST', '/api/rooms', admin, {
        'hotelId': hotel['id'], 'number': '101', 'available': True, 'pricePerNight': 100, 'capacity': 2}, (201,))
    start = dt.date.today() + dt.timedelta(days=90)
    end = start + dt.timedelta(days=2)
    calendar = '/api/rooms/recommend?hotelId=' + str(hotel['id']) + '&startDate=' + str(start) + '&endDate=' + str(end)
    payload = {'requestId': str(uuid.uuid4()), 'hotelId': hotel['id'], 'roomId': room['id'],
               'autoSelect': False, 'startDate': str(start), 'endDate': str(end)}

    # All Hotel writes succeed, but all responses are lost. Compensation is unavailable.
    failure = request(booking_url, 'POST', '/api/bookings', token, payload, (503,))
    assert failure['code'] == 'HOTEL_UNAVAILABLE', failure
    replay = request(booking_url, 'POST', '/api/bookings', token, payload)
    assert replay['status'] == 'CANCELLED' and replay['compensationRequired'], replay
    assert request(hotel_url, 'GET', calendar, token)['totalElements'] == 0
    assert request(hotel_url, 'GET', '/api/rooms/' + str(room['id']), token)['timesBooked'] == 1
    with state.lock:
        assert state.allocation_attempts == 3, state.allocation_attempts
        assert state.allocation_commits == 3, state.allocation_commits
        assert state.release_failures >= 3, state.release_failures
        late_payload = dict(state.last_allocation)
        internal_token = state.internal_token
        state.hide_allocation_replies = False
        state.block_release = False
    print('PASS: three lost replies, one Hotel allocation, CANCELLED with deferred compensation.', flush=True)

    def recovered():
        booking = request(booking_url, 'GET', '/api/bookings/' + replay['id'], token)
        return booking if booking['status'] == 'CANCELLED' and not booking['compensationRequired'] else None

    wait_until(recovered, 'background compensation', timeout=30)
    assert request(hotel_url, 'GET', calendar, token)['totalElements'] == 1
    late = request(hotel_url, 'POST', '/api/rooms/allocate', internal_token, late_payload, (409,))
    assert late['code'] == 'RESERVATION_RELEASED', late
    assert request(hotel_url, 'GET', calendar, token)['totalElements'] == 1
    assert request(hotel_url, 'GET', '/api/rooms/' + str(room['id']), token)['timesBooked'] == 1
    print('PASS: scheduled recovery released the real calendar; late confirmation rejected with 409.', flush=True)

    # A temporary 503 is retried successfully after recovery; the same room is bookable again.
    with state.lock:
        state.transient_allocation_failures = 1
        attempts_before = state.allocation_attempts
    fresh_payload = dict(payload, requestId=str(uuid.uuid4()))
    fresh = request(booking_url, 'POST', '/api/bookings', token, fresh_payload, (201,))
    assert fresh['status'] == 'CONFIRMED', fresh
    with state.lock:
        assert state.allocation_attempts - attempts_before == 2
    same = request(booking_url, 'POST', '/api/bookings', token, fresh_payload)
    assert same['id'] == fresh['id'] and same['status'] == 'CONFIRMED'
    cancelled = request(booking_url, 'DELETE', '/api/bookings/' + fresh['id'], token)
    assert cancelled['status'] == 'CANCELLED' and not cancelled['compensationRequired']
    assert request(hotel_url, 'GET', calendar, token)['totalElements'] == 1
    assert request(hotel_url, 'GET', '/api/rooms/' + str(room['id']), token)['timesBooked'] == 2
    statistics = request(hotel_url, 'GET', '/api/hotels/' + str(hotel['id']) + '/statistics?startDate=' +
                         str(start) + '&endDate=' + str(end), admin)
    assert statistics['bookedNights'] == 0, statistics
    print('PASS: transient 503 recovered by retry; rebooking, replay and cancellation remain consistent.', flush=True)


def main():
    env = environment()
    hotel_url = env.get('FAULT_HOTEL_URL', 'http://127.0.0.1:' + env.get('HOTEL_PORT', '8082')).rstrip('/')
    request(hotel_url, 'GET', '/actuator/health')
    jar = ROOT / 'booking-service/target/booking-service-1.0.0.jar'
    if not jar.exists():
        raise RuntimeError('Build the Booking jar first: ./mvnw -pl booking-service -am package')
    java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else shutil.which('java')
    if not java:
        raise RuntimeError('Java 17+ was not found; set JAVA_HOME.')
    run_dir = ROOT / '.run'
    run_dir.mkdir(exist_ok=True)
    suffix = uuid.uuid4().hex[:8]
    booking_log = run_dir / ('fault-booking-' + suffix + '.log')
    state = FaultState(hotel_url, run_dir / ('fault-proxy-' + suffix + '.jsonl'))
    proxy = ThreadingHTTPServer(('127.0.0.1', 0), FaultProxy)
    proxy.daemon_threads = True
    proxy.fault_state = state
    proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    proxy_thread.start()
    booking_port = unused_port()
    booking_url = f'http://127.0.0.1:{booking_port}'
    env.update({'BOOKING_PORT': str(booking_port), 'HOTEL_DISCOVERY': 'false',
                'HOTEL_BASE_URL': f'http://127.0.0.1:{proxy.server_port}', 'EUREKA_CLIENT_ENABLED': 'false',
                'SPRING_CLOUD_DISCOVERY_ENABLED': 'false', 'SEED_DATA': 'true'})
    process = None
    try:
        with booking_log.open('w') as output:
            process = subprocess.Popen([java, '-Xms64m', '-Xmx256m', '-jar', str(jar),
                '--hotel.read-timeout-ms=400', '--hotel.connect-timeout-ms=400',
                '--hotel.retry-attempts=3', '--hotel.retry-delay-ms=50',
                '--app.recovery.delay-ms=1000', '--app.recovery.pending-timeout-seconds=30'],
                cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)

            def ready():
                if process.poll() is not None:
                    raise RuntimeError(f'Isolated Booking exited; inspect {booking_log}')
                return request(booking_url, 'GET', '/actuator/health', timeout=1)

            wait_until(ready, 'isolated Booking startup', timeout=90)
            run_checks(booking_url, hotel_url, env, state, suffix)
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        proxy.shutdown()
        proxy.server_close()
        print('Fault test logs:', booking_log.relative_to(ROOT), state.log_path.relative_to(ROOT), flush=True)


if __name__ == '__main__':
    main()
