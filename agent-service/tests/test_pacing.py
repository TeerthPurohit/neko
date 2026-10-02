import asyncio
import json
from datetime import datetime

from nanobot.providers.base import LLMResponse, ToolCallRequest

from neko_agent.runtime import IST, AgentRequest, month_pace, run_agent, select_specialist
from tests.test_runtime import FakeProvider


def ms(month, day):
    return int(datetime(2026, month, day, 12, tzinfo=IST).timestamp() * 1000)


def tx(tx_id, amount, category='FOOD', month=10, day=5, **extra):
    row = dict(id=tx_id, occurred_at=ms(month, day), amount_paise=amount, direction='DEBIT', category=category,
               merchant='Shop', status='POSTED', review='CONFIRMED', account_alias='ICICI', updated_at=1)
    row.update(extra)
    return row


def make(transactions, plan=None, budgets=None, question='How am I doing this month?'):
    return AgentRequest(user_id='u', task_id='t', api_key='not-a-real-key', model='xiaomi/mimo-v2.6-pro', question=question,
                        consent=True, last_sync=1, transactions=transactions, budgets=budgets or [], history=[], plan=plan)


def plan(month='2026-10', budget=3_000_000, income=5_000_000):
    return dict(month=month, budget_paise=budget, income_paise=income)


def at(day, month=10):
    return datetime(2026, month, day, 12, tzinfo=IST)


def test_pace_projects_month_end_and_safe_daily_spend():
    pace = month_pace(make([tx('a', 1_600_000)], plan()), at(16))
    assert pace['days_elapsed'] == 16 and pace['days_left'] == 16
    assert pace['spent_paise'] == 1_600_000 and pace['daily_average_paise'] == 100_000
    assert pace['projected_month_end_paise'] == 3_100_000 and pace['projected_overshoot_paise'] == 100_000
    assert pace['remaining_paise'] == 1_400_000 and pace['safe_per_day_paise'] == 87_500
    assert pace['used_pct'] == 53.3 and pace['plan_status'] == 'over_pace'


def test_status_levels():
    assert month_pace(make([tx('a', 800_000)], plan()), at(16))['plan_status'] == 'on_track'
    assert month_pace(make([tx('a', 2_500_000)], plan()), at(31))['plan_status'] == 'watch'
    over = month_pace(make([tx('a', 3_200_000)], plan()), at(20))
    assert over['plan_status'] == 'over_budget' and over['safe_per_day_paise'] == 0 and over['remaining_paise'] == -200_000


def test_other_months_and_unconfirmed_entries_do_not_count():
    rows = [tx('old', 9_000_000, month=9, day=20), tx('draft', 500_000, review='DRAFT'), tx('failed', 400_000, status='FAILED'), tx('real', 100_000)]
    assert month_pace(make(rows, plan()), at(16))['spent_paise'] == 100_000


def test_missing_and_stale_plans_are_reported_not_invented():
    none = month_pace(make([tx('a', 100_000)]), at(16))
    assert none['plan_status'] == 'no_plan' and 'budget_paise' not in none
    stale = month_pace(make([tx('a', 100_000)], plan(month='2026-09')), at(2))
    assert stale['plan_status'] == 'stale_plan' and 'budget_paise' not in stale and stale['plan_month'] == '2026-09'


def test_category_limits_flag_overspend():
    pace = month_pace(make([tx('a', 1_600_000, 'FOOD'), tx('b', 100_000, 'TRANSPORT')], plan(),
                           budgets=[dict(category='FOOD', amount_paise=1_000_000), dict(category='TRANSPORT', amount_paise=500_000)]), at(16))
    food, transport = pace['category_status']
    assert (food['category'], food['state'], food['used_pct']) == ('FOOD', 'over', 160.0)
    assert (transport['category'], transport['state']) == ('TRANSPORT', 'ok')


def test_early_month_projection_is_marked_unreliable_and_partial_coverage_flagged():
    assert month_pace(make([tx('a', 100_000)], plan()), at(2))['projection_reliable'] is False
    assert month_pace(make([tx('a', 100_000)], plan()), at(16))['projection_reliable'] is True
    many = [tx(f'e{i}', 1_000, day=3 + i % 10) for i in range(150)]
    assert month_pace(make(many, plan()), at(16))['coverage_may_be_partial'] is True
    assert month_pace(make([tx('a', 1_000)], plan()), at(16))['coverage_may_be_partial'] is False


def test_pacing_questions_go_to_the_budget_specialist():
    for question in ('Can I afford a 5000 rupee purchase this month?', 'Am I on track?', 'How much is left to spend?', "What's my safe to spend today?"):
        assert select_specialist(make([tx('a', 1)], question=question)) == 'budget', question


def test_agent_sees_the_plan_and_can_read_it_with_a_tool():
    now = datetime.now(IST)
    this_month = dict(month=now.strftime('%Y-%m'), budget_paise=3_000_000, income_paise=5_000_000)
    row = tx('a', 100_000, month=now.month, day=1)
    row['occurred_at'] = int(now.timestamp() * 1000)
    provider = FakeProvider([LLMResponse(None, [ToolCallRequest('p', 'get_plan_status', {})]), LLMResponse('You are on track.')])
    result = asyncio.run(run_agent(make([row], this_month, question='Am I on track this month?'), provider))
    system = provider.messages[0][0]['content']
    assert '"budget_paise": 3000000' in system and '"pace"' in system
    tool_message = next(m for m in provider.messages[-1] if m['role'] == 'tool')
    assert 'projected_month_end_paise' in str(tool_message)
    assert result['reply'] == 'You are on track.'
    assert result['audit'][0] == {'tool': 'get_plan_status', 'transaction_id': None, 'effect': 'read_only'}
