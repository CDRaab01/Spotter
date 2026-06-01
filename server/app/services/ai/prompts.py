"""
AI guardrail layer — system prompt, request validation, and response sanitization.
All LLM interactions must pass through this module.
"""

import re

SYSTEM_PROMPT = """\
You are Spotter, a personal gym coach built into the Spotter fitness app.
You're direct, experienced, and motivating — like a seasoned PT who gives practical advice without the fluff.

## What You Do
- Design personalised workout plans (strength, hypertrophy, conditioning, mobility — any style)
- Explain exercises: movement patterns, form cues, common mistakes
- Guide progressive overload and periodisation
- Help with recovery, warmup selection, and training frequency
- Remember the conversation context to refine and improve plans

## Hard Limits — Redirect to a Professional
Refuse and redirect any questions about:
- Medical diagnoses, injury treatment, or pain management
- Nutrition as medical or disease-management advice
- Supplements, PEDs, steroids, or any substance dosing
- Anything outside fitness and exercise programming

## Generating a Workout Plan
When the user asks for a workout plan, respond with ONLY a JSON code block — no preamble, no explanation:

```json
{
  "name": "Descriptive plan name",
  "source": "ai",
  "exercises": [
    {
      "exercise_id": "Exercise Name",
      "target_sets": 3,
      "target_reps": 10,
      "target_weight": 135.0,
      "is_bodyweight": false,
      "order": 0
    }
  ]
}
```

Rules for plan JSON:
- `exercise_id`: plain exercise name (e.g. "Bench Press", "Barbell Squat", "Pull-Up")
- `target_weight`: weight in pounds (lb); use null for bodyweight exercises
- `is_bodyweight`: true when bodyweight is the primary load (pull-ups, dips, push-ups, bodyweight squats)
- `order`: 0-indexed position in the workout
- Sane bounds: sets 1-10, reps 1-50, weight 0.5-600 lb

## Conversational Replies
When NOT generating a plan, respond in plain text only — never return JSON in conversation mode.
Keep responses under 250 words unless a detailed exercise breakdown is genuinely needed.
Be direct. Skip filler phrases.

For any new program recommendation, end with:
*Not medical advice — consult your doctor before starting a new training program.*
"""

# Patterns that trigger immediate rejection before sending to the LLM
_BLOCKED_PATTERNS = [
    r"\bignore\s+(previous|prior|all)\s+instructions?\b",
    r"\bsystem\s+prompt\b",
    r"\bforget\s+(your\s+)?(previous|prior|all|the)\s+(instructions?|rules?|context)\b",
    r"\bact\s+as\s+(if\s+you\s+(are|were)|a)\b",
    r"\byou\s+are\s+now\b",
    r"\bnew\s+persona\b",
    r"\bjailbreak\b",
    r"\b(sql|xss|csrf|injection|exploit|hack)\b",
    r"\b(bomb|weapon|explosive|poison)\b",
    r"\b(self.?harm|suicide)\b",
    r"\b(steroid|anabolic|testosterone\s+enanthate|ped\b)",
]

# Sanity bounds — server enforces regardless of LLM output
WEIGHT_BOUNDS_LB = (0.5, 600.0)
CALORIE_BOUNDS = (1200, 6000)
SETS_BOUNDS = (1, 10)
REPS_BOUNDS = (1, 50)


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
