"""The chat path must degrade gracefully when LM Studio answers 200 with a body we
can't use (no choices / non-JSON / null content) — never a raw 500."""

from unittest.mock import AsyncMock, MagicMock, patch


def _mock_resp(json_value=None, json_exc=None, text="body"):
    m = MagicMock()
    m.raise_for_status = MagicMock()
    m.text = text
    if json_exc is not None:
        m.json.side_effect = json_exc
    else:
        m.json.return_value = json_value
    return m


async def _post_greeting(auth_client, mock_resp):
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        return await auth_client.post(
            "/ai/chat", json={"messages": [{"role": "user", "content": "hey"}]}
        )


async def test_missing_choices_returns_502_not_500(auth_client):
    resp = await _post_greeting(auth_client, _mock_resp(json_value={}))
    assert resp.status_code == 502


async def test_non_json_body_returns_502_not_500(auth_client):
    resp = await _post_greeting(auth_client, _mock_resp(json_exc=ValueError("not json")))
    assert resp.status_code == 502


async def test_null_content_returns_soft_reply(auth_client):
    resp = await _post_greeting(
        auth_client, _mock_resp(json_value={"choices": [{"message": {"content": None}}]})
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["reply"].strip()  # a friendly non-empty reply, not a crash
    assert body["suggested_plan"] is None
    assert body["suggested_program"] is None


async def test_empty_content_returns_soft_reply(auth_client):
    resp = await _post_greeting(
        auth_client, _mock_resp(json_value={"choices": [{"message": {"content": "   "}}]})
    )
    assert resp.status_code == 200
    assert resp.json()["reply"].strip()


def test_system_prompt_blocks_intake_on_greeting():
    from app.services.ai.prompts import SYSTEM_PROMPT

    assert "Never begin intake" in SYSTEM_PROMPT
