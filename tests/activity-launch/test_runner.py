import unittest

from run import CONTROL, NOT_FOUND, validate


class LaunchOracleTests(unittest.TestCase):
    def observation(self, outcome):
        return {"caller": CONTROL, "uid": 10234, "targetVisible": True, "outcome": outcome,
                "originalComponent": "{fixture/component}",
                "exceptionMessage": "Unable to find {fixture/component}"}

    def check_result(self, outcome, expected, marker=False, foreground=False):
        return validate(self.observation(outcome), expected, True,
                        marker, foreground, CONTROL, 10234)

    def test_hidden_start_requires_activity_not_found(self):
        self.assertEqual([], self.check_result(NOT_FOUND, NOT_FOUND))
        self.assertTrue(self.check_result("java.lang.SecurityException", NOT_FOUND))
        self.assertTrue(self.check_result("STARTED", NOT_FOUND))

    def test_denial_cannot_launch_target(self):
        self.assertTrue(self.check_result(NOT_FOUND, NOT_FOUND, marker=True))
        self.assertTrue(self.check_result(NOT_FOUND, NOT_FOUND, foreground=True))

    def test_allowed_start_requires_lifecycle_and_foreground_evidence(self):
        self.assertEqual([], self.check_result("STARTED", "STARTED", True, True))
        self.assertTrue(self.check_result("STARTED", "STARTED", False, True))
        self.assertTrue(self.check_result("STARTED", "STARTED", True, False))

    def test_wrong_uid_or_visibility_invalidates_fixture(self):
        observation = self.observation(NOT_FOUND)
        observation["uid"] = 2000
        self.assertTrue(validate(observation, NOT_FOUND, True, False, False, CONTROL, 10234))
        observation = self.observation(NOT_FOUND)
        observation["targetVisible"] = False
        self.assertTrue(validate(observation, NOT_FOUND, True, False, False, CONTROL, 10234))

    def test_not_found_cannot_leak_a_replacement_component(self):
        observation = self.observation(NOT_FOUND)
        observation["exceptionMessage"] = "Unable to find {/}"
        self.assertTrue(validate(observation, NOT_FOUND, True, False, False, CONTROL, 10234))


if __name__ == "__main__":
    unittest.main()
