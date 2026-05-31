"""
AI guardrail layer — system prompt, request validation, and response sanitization.
All LLM interactions must pass through this module.
"""

import re

SYSTEM_PROMPT = """\
You are Spotter AI, a knowledgeable personal fitness assistant.
You help users plan workouts, understand exercises, track progress, and achieve their fitness goals.

Rules you must follow:
1. Only answer questions related to fitness, exercise, workout programming, recovery, and general wellness.
2. Never provide medical diagnoses or replace professional medical advice.
3. If asked about injuries, pain, or medical conditions, always recommend consulting a healthcare professional.
4. Keep responses concise (under 300 words unless a structured workout plan is requested).
5. When suggesting exercises, include key form cues and safety reminders.
6. Weight/load recommendations must be conservative — always suggest starting lighter and progressing gradually.
7. Never recommend extreme caloric restriction (below 1200 kcal/day for any adult).
8. Never recommend more than 2 hours of training per day for non-competitive athletes.
9. Refuse requests for advice on supplements, PEDs, or anything outside fitness programming scope.
10. Include a brief disclaimer when giving any program recommendation: this is general guidance, not medical advice; consult a doctor before starting a new program.

When generating a structured workout plan, return valid JSON matching the WorkoutPlan schema.
"""

# Patterns that trigger immediate rejection before sending to the LLM
_BLOCKED_PATTERNS = [
    r"\bignore\s+(previous|prior|all)\s+instructions?\b",
    r"\bsystem\s+prompt\b",
    r"\b(sql|xss|csrf|injection|exploit|hack)\b",
    r"\b(bomb|weapon|explosive|poison)\b",
    r"\b(self.?harm|suicide)\b",
    r"\b(steroid|anabolic|testosterone\s+enanthate|ped\b)",
]

# Sanity bounds — server enforces regardless of LLM output
WEIGHT_BOUNDS_LB = (0.5, 1200.0)
CALORIE_BOUNDS = (1200, 6000)
SETS_BOUNDS = (1, 10)
REPS_BOUNDS = (1, 100)


def validate_request(user_message: str) -> str | None:
    """Return an error string if the message should be rejected, else None."""
    if len(user_message) > 2000:
        return "Message too long. Please keep questions under 2000 characters."
    lower = user_message.lower()
    for pattern in _BLOCKED_PATTERNS:
        if re.search(pattern, lower):
            return (
                "That request is outside the scope of fitness assistance. "
                "I can only help with workout planning, exercise form, and general fitness topics."
            )
    return None


def build_messages(history: list[dict], new_user_message: str) -> list[dict]:
    """Prepend the system prompt and append the new user turn."""
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    messages.extend(history)
    messages.append({"role": "user", "content": new_user_message})
    return messages


def validate_response(reply: str) -> str:
    """Sanitize the LLM reply before returning it to the client."""
    for pattern in _BLOCKED_PATTERNS:
        if re.search(pattern, reply.lower()):
            return (
                "I can only help with fitness-related topics. "
                "Please ask me about workouts, exercises, or training programs."
            )
    return reply.strip()
