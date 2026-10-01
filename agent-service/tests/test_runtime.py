import asyncio
import copy
import json

import pytest
from nanobot.providers.base import LLMProvider, LLMResponse, ToolCallRequest

from neko_agent.runtime import AgentRequest, Correction, run_agent, summarize


class FakeProvider(LLMProvider):
    def __init__(self, replies):
        super().__init__(provider_name='openrouter')
        self.replies = iter(replies)
        self.messages = []
        self.cost = 0.001

    def get_default_model(self):
        return 'xiaomi/mimo-v2.6-pro'

    async def chat(self, messages, **kwargs):
        self.messages.append(copy.deepcopy(messages))
        return next(self.replies)


def request(user='one', tx='entry'):
    return AgentRequest(user_id=user, task_id='job', api_key='not-a-real-key',
        model='xiaomi/mimo-v2.6-pro', question='Categorize this entry', consent=True,
        last_sync=123, transactions=[dict(id=tx, occurred_at=100, amount_paise=999,
        direction='DEBIT', category='OTHER', merchant='Lunch', status='POSTED',
        review='DRAFT', account_alias='ICICI', updated_at=101)], budgets=[], history=[])


def draft(tx='entry'):
    return ToolCallRequest('tool1', 'draft_ledger_change',
        dict(transaction_id=tx, category='FOOD', reason='Lunch'))


def test_real_graph_and_nanobot_runner_only_draft_changes():
    data = request()
    original = data.model_dump()
    provider = FakeProvider([LLMResponse(None, [draft()]), LLMResponse('Review the food category suggestion.')])
    result = asyncio.run(run_agent(data, provider))
    assert result['workflow'] == ['coordinator', 'context', 'ledger_agent', 'confirmation_boundary']
    assert result['requires_confirmation']
    assert result['proposals'][0]['expected_updated_at'] == 101
    assert result['audit'][0]['effect'] == 'draft_only'
    assert data.model_dump() == original
    assert len(provider.messages) == 2


def test_unregistered_shell_tool_and_unknown_transaction_are_rejected():
    provider = FakeProvider([LLMResponse(None, [ToolCallRequest('x', 'exec', {'command': 'delete ledger'}), draft('another-user')]), LLMResponse('No changes applied.')])
    result = asyncio.run(run_agent(request(), provider))
    assert result['proposals'] == []
    results = [m for m in provider.messages[-1] if m['role'] == 'tool']
    assert any('not found' in str(m).lower() or 'unknown' in str(m).lower() or 'not available' in str(m).lower() for m in results)


def test_parallel_users_have_isolated_tool_state():
    async def run_both():
        return await asyncio.gather(*[run_agent(request(user, user), FakeProvider([
            LLMResponse(None, [draft(user)]), LLMResponse('Review the suggestion.')])) for user in ('one', 'two')])
    one, two = asyncio.run(run_both())
    assert one['proposals'][0]['transaction_id'] == 'one'
    assert two['proposals'][0]['transaction_id'] == 'two'
    assert len(one['proposals']) == len(two['proposals']) == 1


def test_consent_and_paid_model_gates_run_before_provider():
    data = request()
    data.consent = False
    with pytest.raises(ValueError, match='consent'):
        asyncio.run(run_agent(data, FakeProvider([])))
    data.consent = True
    data.model = 'openrouter/free'
    with pytest.raises(ValueError, match='paid model'):
        asyncio.run(run_agent(data, FakeProvider([])))


def test_report_excludes_drafts_failures_and_own_transfers():
    tx = request().transactions[0]
    rows = [tx.model_copy(update={'review': 'CONFIRMED'}),
            tx.model_copy(update={'review': 'CONFIRMED', 'transfer_id': 'pair'}),
            tx.model_copy(update={'review': 'CONFIRMED', 'status': 'FAILED'}), tx]
    assert summarize(rows)['spending_paise'] == 999
    assert summarize(rows)['drafts'] == 1


def test_service_authentication_and_validation_do_not_echo_secrets(monkeypatch):
    from fastapi.testclient import TestClient
    from neko_agent.server import app
    monkeypatch.setenv('AGENT_SERVICE_TOKEN', 't' * 64)
    client = TestClient(app)
    assert client.post('/internal/run', json={}).status_code == 401
    response = client.post('/internal/run', headers={'Authorization': 'Bearer ' + 't' * 64},
        json={'api_key': 'secret-must-not-appear', 'sms_body': 'private SMS'})
    assert response.status_code == 400
    assert 'secret-must-not-appear' not in response.text
    assert 'private SMS' not in response.text


def test_saved_corrections_are_scoped_and_do_not_leak_between_users():
    data = request()
    data.question = 'Review my budget'
    data.corrections = [Correction(id='budget-rule', scope='budget', behavior='Too many alerts',
        correction='Only mention a budget alert at 90 percent.', created_at=1),
        Correction(id='ledger-rule', scope='ledger', behavior='Wrong category',
        correction='My cafe purchases are FOOD.', created_at=2)]
    provider = FakeProvider([LLMResponse('Budget reviewed.')])
    result = asyncio.run(run_agent(data, provider))
    assert result['specialist'] == 'budget'
    assert result['correction_ids'] == ['budget-rule']
    system = provider.messages[0][0]['content']
    assert '90 percent' in system
    assert 'My cafe purchases' not in system
    other = FakeProvider([LLMResponse('Budget reviewed.')])
    other_data = request('other')
    other_data.question = 'Review my budget'
    asyncio.run(run_agent(other_data, other))
    assert '90 percent' not in other.messages[0][0]['content']


def test_followup_specialist_cannot_draft_ledger_changes():
    data = request()
    data.task_kind = 'checkin'
    provider = FakeProvider([LLMResponse(None, [draft()]), LLMResponse('Please review your drafts.')])
    result = asyncio.run(run_agent(data, provider))
    assert result['specialist'] == 'followup'
    assert result['proposals'] == []
    assert result['audit'] == []
