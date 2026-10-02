import asyncio
import json

import httpx
import pytest

from neko_agent.routing import cheap_candidates, choose_model
from neko_agent.runtime import OpenRouterProvider


def model(slug, prompt='0.00000002', completion='0.0000001', tools=True):
    return {'id': slug, 'pricing': {'prompt': prompt, 'completion': completion},
            'context_length': 32000, 'supported_parameters': ['max_tokens', *(['tools'] if tools else [])],
            'description': 'General reasoning model with tools'}


def test_dynamic_catalog_includes_other_cheap_models_and_filters_ineligible_ones():
    rows = [model('other/cheap-agent'), model('other/expensive', prompt='0.000005'),
            model('other/no-tools', tools=False), model('other/free:free'),
            model('typesafe/jev-router'), model('other/free', prompt='0', completion='0')]
    transport = httpx.MockTransport(lambda r: httpx.Response(200, json={'data': rows}))
    assert list(asyncio.run(cheap_candidates(transport))) == ['other/cheap-agent']


@pytest.mark.parametrize('selection,confidence,expected_source', [
    ('other/cheap-agent', 0.95, 'jev'), ('unauthorized/expensive', 0.99, 'jev_uncertain'),
    ('other/cheap-agent', 0.3, 'jev_uncertain')])
def test_jev_cannot_choose_outside_dynamic_candidates(selection, confidence, expected_source):
    calls = []
    def handler(r):
        calls.append(r)
        if r.method == 'GET':
            return httpx.Response(200, json={'data': [model('other/cheap-agent')]})
        data = json.loads(r.content)
        assert list(data['questions']['model']['criteria']) == ['other/cheap-agent']
        return httpx.Response(200, json={'answers': {'model': {'choice': selection, 'confidence': confidence}},
                                        'usage': {'cost': 0.0001}})
    route = asyncio.run(choose_model('fake-key', 'Explain my spending', 'summary', [], [], httpx.MockTransport(handler)))
    assert route.model == 'other/cheap-agent'
    assert route.source == expected_source
    assert len(calls) == 2


def test_provider_cost_bound_stops_before_network_and_applies_price_caps():
    calls = []
    def handler(r):
        body = json.loads(r.content)
        assert body['provider']['max_price'] == {'prompt': 0.5, 'completion': 1}
        assert body['provider']['data_collection'] == 'deny'
        calls.append(r)
        return httpx.Response(200, json={'choices': [{'message': {'content': 'Done'}}], 'usage': {'cost': 0.001}})
    provider = OpenRouterProvider('fake-key', 'other/cheap-agent', httpx.MockTransport(handler), max_cost=0.01)
    asyncio.run(provider.chat([{'role': 'user', 'content': 'Hello'}]))
    with pytest.raises(ValueError, match='cost allowance'):
        asyncio.run(provider.chat([{'role': 'user', 'content': 'x' * 50000}]))
    assert len(calls) == 1
