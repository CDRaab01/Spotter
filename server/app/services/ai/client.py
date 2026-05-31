import httpx
from fastapi import HTTPException, status

from app.config import settings
from app.schemas.ai import ChatRequest, ChatResponse
from app.services.ai.prompts import build_messages, validate_request, validate_response


async def chat(req: ChatRequest) -> ChatResponse:
    last_user = next(
        (m.content for m in reversed(req.messages) if m.role == "user"), ""
    )
    error = validate_request(last_user)
    if error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=error
        )

    history = [m.model_dump() for m in req.messages[:-1]]
    messages = build_messages(history, last_user)

    async with httpx.AsyncClient(timeout=60.0) as client:
        try:
            resp = await client.post(
                f"{settings.lm_studio_base_url}/chat/completions",
                json={
                    "model": settings.lm_studio_model,
                    "messages": messages,
                    "temperature": 0.7,
                },
            )
            resp.raise_for_status()
        except httpx.HTTPStatusError as e:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"LM Studio returned {e.response.status_code}",
            )
        except httpx.RequestError:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="LM Studio is not reachable",
            )

    data = resp.json()
    raw_reply = data["choices"][0]["message"]["content"]
    return ChatResponse(reply=validate_response(raw_reply))
