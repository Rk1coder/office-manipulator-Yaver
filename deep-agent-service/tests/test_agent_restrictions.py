"""Verifies the Deep Agent is restricted to create_word_document only -- the
security boundary from migration Phase 3 ("agent'a sınırsız dosya sistemi veya
shell erişimi verme"). No LLM calls here; this only checks the registered
HarnessProfile deepagents will apply to our ChatAnthropic model.
"""
import deepagents.profiles.harness.harness_profiles as harness

import app.agent  # noqa: F401 -- importing triggers register_harness_profile at module load

EXPECTED_EXCLUDED = {"ls", "read_file", "write_file", "edit_file", "delete", "glob", "grep", "execute", "task"}


def test_anthropic_profile_excludes_default_filesystem_and_shell_tools():
    profile = harness._get_harness_profile("anthropic")
    assert profile is not None
    assert profile.excluded_tools == frozenset(EXPECTED_EXCLUDED)


def test_create_word_document_is_not_excluded():
    profile = harness._get_harness_profile("anthropic")
    assert "create_word_document" not in profile.excluded_tools
