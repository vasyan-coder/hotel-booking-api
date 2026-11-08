"""Deterministic readiness regression tests; no running services required."""
import unittest
from unittest.mock import patch

import smoke


class ReadinessTest(unittest.TestCase):
    def setUp(self):
        self.now = 0
        self.clock = patch.object(smoke.time, 'monotonic', side_effect=lambda: self.now)
        self.sleep = patch.object(smoke.time, 'sleep', side_effect=self.advance)
        self.clock.start()
        self.sleep.start()
        self.addCleanup(self.clock.stop)
        self.addCleanup(self.sleep.stop)

    def advance(self, seconds):
        self.now += seconds

    def test_waits_for_booking_to_discover_hotel_even_if_gateway_is_ready(self):
        page = {'content': []}
        replies = [(page, 200), ({'code': 'HOTEL_UNAVAILABLE'}, 503), (page, 200)]
        with patch.object(smoke, 'request', side_effect=replies) as request:
            smoke.wait_for_services('test-token')
        self.assertEqual(3, request.call_count)
        calls = request.call_args_list
        self.assertEqual('/api/hotels', calls[0].args[1])
        self.assertTrue(calls[1].args[1].startswith('/api/bookings/recommendations?'))
        self.assertEqual(calls[1], calls[2])
        self.assertTrue(all(call.args[0] == 'GET' for call in calls))

    def test_transient_transport_failure_can_recover(self):
        page = {'content': []}
        with patch.object(smoke, 'request', side_effect=[OSError('Connection refused'), (page, 200)]):
            self.assertEqual(page, smoke.wait_for_get('/api/hotels', 'test-token'))

    def test_persistent_unavailability_has_a_deadline(self):
        with patch.object(smoke, 'request', return_value=({'code': 'HOTEL_UNAVAILABLE'}, 503)) as request:
            with self.assertRaisesRegex(AssertionError, 'Service chain did not become ready'):
                smoke.wait_for_get('/api/hotels', 'test-token', timeout=2)
        self.assertEqual(2, self.now)
        self.assertEqual(3, request.call_count)

    def test_unexpected_http_errors_are_not_hidden_by_readiness_retries(self):
        for status in (401, 403, 500):
            with self.subTest(status=status):
                with patch.object(smoke, 'request', side_effect=AssertionError(str(status))) as request:
                    with self.assertRaisesRegex(AssertionError, str(status)):
                        smoke.wait_for_get('/api/hotels', 'test-token')
                self.assertEqual(1, request.call_count)

    def test_invalid_success_body_fails_immediately(self):
        with patch.object(smoke, 'request', return_value=({'unexpected': 'payload'}, 200)) as request:
            with self.assertRaisesRegex(AssertionError, 'Invalid readiness response'):
                smoke.wait_for_get('/api/hotels', 'test-token')
        self.assertEqual(1, request.call_count)


if __name__ == '__main__':
    unittest.main()
