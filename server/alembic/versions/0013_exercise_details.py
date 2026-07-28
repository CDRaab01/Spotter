"""exercise details: instructions + secondary muscles

Adds ``instructions`` (form cues + execution steps) and ``secondary_muscles``
(comma-separated lowercase muscle names) to the exercise catalog, and backfills
both for every seeded exercise (the 0002 + 0009 seed lists), matched by name.
The columns stay nullable so user-created rows without content are fine.

Revision ID: 0013
Revises: 0012
Create Date: 2026-07-28
"""

import sqlalchemy as sa
from alembic import op

revision = "0013"
down_revision = "0012"
branch_labels = None
depends_on = None

# name -> (instructions, secondary_muscles). Covers every exercise seeded by
# 0002_seed_exercises and 0009_seed_accessory_exercises; UPDATE-by-name so rows
# renamed or removed out-of-band are silently skipped.
DETAILS: dict[str, tuple[str, str]] = {
    # ── 0002: barbell ──────────────────────────────────────────────────────
    "Barbell Back Squat": (
        "Set the bar across your upper traps, feet shoulder-width with toes slightly out. "
        "Brace your core, then sit down and back until your thighs reach at least parallel, "
        "keeping the bar over mid-foot. Drive up through your whole foot without letting the "
        "knees cave inward — knee valgus under load is the most common fault.",
        "glutes, hamstrings, core, lower back",
    ),
    "Barbell Front Squat": (
        "Rack the bar on your front delts with elbows high and a loose fingertip grip. Squat "
        "straight down between your hips, keeping the torso as upright as possible. Stand back "
        "up by driving the elbows up — letting them drop tips you forward and dumps the bar.",
        "quads, glutes, core, upper back",
    ),
    "Conventional Deadlift": (
        "Stand with the bar over mid-foot, hinge down and grip just outside your legs. Flatten "
        "your back, pull the slack out of the bar, then push the floor away and stand tall with "
        "the bar dragging up your shins. Never let the lower back round — if it does, the weight "
        "is too heavy.",
        "glutes, hamstrings, traps, forearms, core",
    ),
    "Romanian Deadlift": (
        "Start standing with the bar at your hips, knees slightly bent. Push your hips straight "
        "back and lower the bar down your thighs until you feel a deep hamstring stretch, "
        "usually just below the knees. Squeeze the glutes to stand back up; bending the knees "
        "more to get depth turns it into a squat and defeats the purpose.",
        "glutes, lower back, forearms",
    ),
    "Bench Press": (
        "Lie with eyes under the bar, feet planted, shoulder blades pinched together and down. "
        "Lower the bar under control to your mid-chest with elbows at roughly 45 degrees, then "
        "press back up to lockout over your shoulders. Don't bounce the bar off your chest or "
        "let your elbows flare straight out.",
        "front delts, triceps",
    ),
    "Incline Bench Press": (
        "Set the bench to 30-45 degrees and grip the bar slightly wider than shoulder width. "
        "Lower the bar to your upper chest, keeping the shoulder blades retracted, then press "
        "up and slightly back to lockout. Too steep an incline shifts the work to the shoulders "
        "and off the upper chest.",
        "front delts, triceps",
    ),
    "Overhead Press": (
        "Stand with the bar at your collarbones, grip just outside the shoulders, glutes and "
        "core tight. Press the bar straight overhead, moving your head back to give it a path, "
        "then push your head through at the top so the bar finishes over mid-foot. Don't lean "
        "back and turn it into an incline press.",
        "triceps, upper chest, core, traps",
    ),
    "Barbell Row": (
        "Hinge to roughly 45 degrees with a flat back, bar hanging at arm's length. Pull the "
        "bar to your lower ribs, driving the elbows back and squeezing the shoulder blades "
        "together, then lower under control. Avoid heaving with the lower back to move the "
        "weight — if your torso bounces, lighten the bar.",
        "biceps, rear delts, lower back, forearms",
    ),
    "Barbell Curl": (
        "Stand tall with a shoulder-width underhand grip, elbows pinned to your sides. Curl "
        "the bar to shoulder height without moving the upper arms, then lower all the way to "
        "full elbow extension. Swinging the hips to start the rep robs the biceps of the work.",
        "forearms, front delts",
    ),
    "Close-Grip Bench Press": (
        "Grip the bar at about shoulder width and keep the elbows tucked close to your sides. "
        "Lower the bar to your lower chest, then press up focusing on driving through the "
        "triceps. Going too narrow strains the wrists without adding tricep work.",
        "chest, front delts",
    ),
    "Good Morning": (
        "Set the bar on your upper back as for a squat, feet hip-width, knees soft. Hinge at "
        "the hips and push them back until your torso approaches parallel, keeping the back "
        "flat, then squeeze the glutes to stand. Start light — rounding the spine under load "
        "here is the classic mistake.",
        "glutes, lower back",
    ),
    "Rack Pull": (
        "Set the bar on pins at or just below knee height. Grip as for a deadlift, brace, and "
        "stand tall by driving the hips through, finishing with glutes squeezed. Don't lean "
        "back at lockout or slam the bar down — control both directions.",
        "glutes, traps, forearms, lower back",
    ),
    # ── 0002: dumbbell ─────────────────────────────────────────────────────
    "Dumbbell Bench Press": (
        "Lie on a flat bench with a dumbbell in each hand at chest level. Press both bells up "
        "and slightly together to lockout over your shoulders, then lower under control until "
        "you feel a stretch across the chest. Don't let the bells drift apart or clang together "
        "at the top.",
        "front delts, triceps",
    ),
    "Dumbbell Row": (
        "Support one hand and knee on a bench, back flat, dumbbell hanging straight down. Pull "
        "the bell to your hip, driving the elbow back rather than out, then lower to a full "
        "stretch. Rotating the torso to hoist the weight is momentum, not rowing.",
        "biceps, rear delts, forearms",
    ),
    "Dumbbell Shoulder Press": (
        "Sit or stand with a dumbbell at each shoulder, palms forward, core braced. Press both "
        "bells overhead until your arms lock out with biceps by your ears, then lower back to "
        "shoulder height. Avoid arching the lower back to grind out reps.",
        "triceps, upper chest, traps",
    ),
    "Dumbbell Romanian Deadlift": (
        "Hold dumbbells in front of your thighs, knees slightly bent. Push your hips back and "
        "slide the bells down your legs until your hamstrings are fully stretched, then drive "
        "the hips forward to stand. Keep the bells close — letting them drift forward loads "
        "the lower back.",
        "glutes, lower back, forearms",
    ),
    "Dumbbell Curl": (
        "Stand with dumbbells at your sides, palms forward, elbows pinned. Curl one or both "
        "bells to shoulder height, squeeze, then lower to full extension. Keep the shoulders "
        "still — the upper arm should not swing forward.",
        "forearms, front delts",
    ),
    "Dumbbell Lateral Raise": (
        "Stand with dumbbells at your sides, a slight bend in the elbows. Raise the bells out "
        "to shoulder height, leading with the elbows, then lower slowly. Go light — swinging "
        "heavy bells up with momentum turns it into a trap exercise.",
        "traps, front delts",
    ),
    "Dumbbell Overhead Tricep Extension": (
        "Hold one dumbbell overhead with both hands cupping the top plate. Lower it behind "
        "your head by bending the elbows, keeping the upper arms vertical and close to your "
        "ears, then extend back to lockout. Flaring the elbows out shifts the load off the "
        "triceps.",
        "shoulders, core",
    ),
    "Goblet Squat": (
        "Hold a dumbbell vertically against your chest with both hands. Squat down between "
        "your knees, keeping your chest up and elbows inside the knees, until your thighs "
        "reach parallel or below, then drive back up. Letting the weight pull you forward "
        "onto your toes is the usual fault.",
        "quads, glutes, core",
    ),
    "Dumbbell Reverse Lunge": (
        "Stand tall holding dumbbells at your sides. Step one leg back and lower until both "
        "knees hit roughly 90 degrees, front shin vertical, then push through the front heel "
        "to return. Keep the torso upright — collapsing forward loads the knee instead of the "
        "glute.",
        "glutes, hamstrings, core",
    ),
    # ── 0002: bodyweight ───────────────────────────────────────────────────
    "Push-Up": (
        "Set your hands slightly wider than your shoulders, body in a straight line from head "
        "to heels. Lower your chest to just above the floor with elbows at about 45 degrees, "
        "then press back up. Sagging hips or a piked butt means the core isn't braced.",
        "front delts, triceps, core",
    ),
    "Pull-Up": (
        "Hang from the bar with an overhand grip just outside shoulder width. Pull your chin "
        "over the bar by driving the elbows down and back, then lower all the way to a dead "
        "hang. Half reps at the bottom shortchange the lats — full extension every rep.",
        "biceps, rear delts, forearms, core",
    ),
    "Dip": (
        "Support yourself on parallel bars, arms locked, then lower by bending the elbows "
        "until your shoulders drop just below them. Press back up to lockout, leaning slightly "
        "forward to involve the chest. Don't sink into shrugged shoulders at the bottom — keep "
        "them pulled down.",
        "chest, front delts",
    ),
    "Bodyweight Squat": (
        "Stand with feet shoulder-width apart, toes slightly out, arms in front for balance. "
        "Sit down and back until your thighs reach at least parallel, keeping heels down and "
        "chest up, then stand tall. Letting the knees collapse inward is the fault to watch.",
        "glutes, hamstrings, core",
    ),
    "Lunge": (
        "From standing, step one foot forward and lower until both knees are bent to about "
        "90 degrees, back knee just above the floor. Push through the front heel to step back "
        "to standing, and alternate legs. A too-short stride slams the front knee past the "
        "toes.",
        "glutes, hamstrings, core",
    ),
    "Glute Bridge": (
        "Lie on your back with knees bent and feet flat, close to your hips. Drive through the "
        "heels and squeeze the glutes to lift your hips into a straight line from knees to "
        "shoulders, pause, then lower. Pushing through the toes or arching the lower back "
        "means the glutes aren't doing the work.",
        "hamstrings, core",
    ),
    "Plank": (
        "Set up on your forearms and toes, elbows under shoulders, body in one straight line. "
        "Brace your abs and glutes and breathe steadily while holding the position. The rep is "
        "over the moment the hips sag or hike up — quality time beats long sloppy holds.",
        "shoulders, glutes, lower back",
    ),
    "Hollow Hold": (
        "Lie on your back, press your lower back into the floor, and lift your shoulders and "
        "legs a few inches off the ground with arms extended overhead. Hold the banana shape "
        "while breathing. If the lower back pops off the floor, bend the knees or raise the "
        "legs higher to shorten the lever.",
        "hip flexors, quads",
    ),
    "Mountain Climber": (
        "Start in a high plank with hands under shoulders. Drive one knee toward your chest, "
        "then switch legs in a running rhythm while keeping the hips level and low. Bouncing "
        "the hips up to make the movement easier takes the core out of it.",
        "shoulders, hip flexors, quads",
    ),
    # ── 0002: cable / machine ──────────────────────────────────────────────
    "Lat Pulldown": (
        "Sit with thighs secured under the pads and grip the bar wider than your shoulders. "
        "Pull the bar to your upper chest, driving the elbows down and slightly back, then let "
        "it return to a full overhead stretch. Leaning far back and heaving turns it into a "
        "row and cheats the lats.",
        "biceps, rear delts, forearms",
    ),
    "Seated Cable Row": (
        "Sit tall with knees soft and grab the handle at arm's length. Pull it to your stomach, "
        "squeezing the shoulder blades together with the chest up, then let the weight stretch "
        "you forward under control. Rocking the torso back and forth is momentum, not back "
        "work.",
        "biceps, rear delts, lower back",
    ),
    "Cable Curl": (
        "Stand facing a low pulley with an underhand grip on the bar, elbows at your sides. "
        "Curl to shoulder height against the constant cable tension, then lower to full "
        "extension without letting the stack touch down. Stepping too close to the pulley "
        "kills the tension at the bottom.",
        "forearms",
    ),
    "Leg Press": (
        "Sit in the machine with feet shoulder-width on the platform. Lower the sled until "
        "your knees reach about 90 degrees or slightly deeper, keeping your lower back and "
        "hips glued to the pad, then press back up without locking the knees hard. Letting "
        "the hips curl off the seat at the bottom rounds the spine under load.",
        "glutes, hamstrings, calves",
    ),
    "Leg Curl": (
        "Lie or sit in the machine with the pad against your lower calves. Curl your heels "
        "toward your glutes, squeezing the hamstrings hard at the end, then return slowly to "
        "full extension. Don't hike your hips off the pad to move more weight.",
        "calves, glutes",
    ),
    "Leg Extension": (
        "Sit with the pad on your shins just above the ankles, knees lined up with the "
        "machine's pivot. Extend your legs to just short of a hard lockout, pause, then lower "
        "under control. Kicking the weight up fast and letting it crash down does nothing for "
        "the quads.",
        "hip flexors",
    ),
    # ── 0009: chest accessories ────────────────────────────────────────────
    "Decline Bench Press": (
        "Lie on a decline bench with your legs secured and grip the bar slightly wider than "
        "shoulder width. Lower the bar to your lower chest, then press up to lockout over "
        "your shoulders. The stroke is shorter than flat bench — control the bar rather than "
        "bouncing it.",
        "front delts, triceps",
    ),
    "Dumbbell Incline Press": (
        "Set the bench to 30-45 degrees and start with dumbbells at your upper chest. Press "
        "both bells up and slightly together to lockout, then lower to a full stretch across "
        "the upper chest. Don't let the bells drift back over your face — keep them stacked "
        "over the elbows.",
        "front delts, triceps",
    ),
    "Dumbbell Fly": (
        "Lie on a flat bench with dumbbells over your chest, palms facing each other and a "
        "soft bend in the elbows. Open your arms in a wide arc until you feel a stretch across "
        "the chest, then squeeze the bells back together along the same arc. Bending the "
        "elbows more as you tire turns it into a press — go lighter instead.",
        "front delts, biceps",
    ),
    "Cable Crossover": (
        "Set both pulleys high and take a small step forward with a slight lean. With a soft "
        "elbow bend, sweep the handles down and together in front of your chest, squeeze, and "
        "return to a full stretch. Keep the shoulder blades set — shrugging as you press the "
        "handles together shifts work to the delts.",
        "front delts",
    ),
    "Pec Deck": (
        "Sit with your back flat on the pad, forearms or hands on the arms of the machine at "
        "chest height. Squeeze the arms together in front of you, hold the contraction, then "
        "open to a comfortable stretch. Don't let the weight yank your shoulders back at the "
        "stretch position.",
        "front delts",
    ),
    # ── 0009: back accessories ─────────────────────────────────────────────
    "Chin-Up": (
        "Hang from the bar with an underhand, shoulder-width grip. Pull your chin over the bar, "
        "driving the elbows down to your sides, then lower to a dead hang. Kipping the legs to "
        "get over the bar cheats both the lats and the biceps.",
        "biceps, forearms, core",
    ),
    "Inverted Row": (
        "Set a bar at waist height and hang beneath it with your body straight, heels on the "
        "floor. Pull your chest to the bar, squeezing the shoulder blades together, then lower "
        "with control. The straighter your body, the harder it gets — don't let the hips sag.",
        "biceps, rear delts, core",
    ),
    "T-Bar Row": (
        "Straddle the bar, hinge to about 45 degrees with a flat back, and grip the handles. "
        "Pull the weight to your chest, driving the elbows back, then lower to a full stretch. "
        "Standing up as you pull is the usual cheat — the torso angle should not change.",
        "biceps, rear delts, lower back",
    ),
    "Chest-Supported Row": (
        "Lie chest-down on an incline bench with a dumbbell in each hand hanging straight "
        "down. Row both bells to your hips, squeezing the shoulder blades, then lower to a "
        "full stretch. The bench removes momentum — if you must jerk the weight up, it's too "
        "heavy.",
        "biceps, rear delts",
    ),
    "Straight-Arm Pulldown": (
        "Face a high pulley and grip the bar with straight arms at shoulder height. Keeping "
        "only a soft bend in the elbows, sweep the bar down to your thighs in an arc, feeling "
        "the lats, then return overhead with control. Bending the elbows turns it into a "
        "tricep pushdown.",
        "triceps, rear delts, core",
    ),
    "Dumbbell Shrug": (
        "Stand tall with heavy dumbbells at your sides. Shrug your shoulders straight up "
        "toward your ears, hold the squeeze for a beat, then lower fully. Rolling the "
        "shoulders in circles adds nothing and irritates the joint — straight up and down.",
        "forearms, upper back",
    ),
    # ── 0009: shoulder accessories ─────────────────────────────────────────
    "Arnold Press": (
        "Start seated with dumbbells at shoulder height, palms facing you. Press overhead "
        "while rotating your palms to face forward, finishing locked out, then reverse the "
        "rotation on the way down. Keep the core braced — the rotation invites a lower-back "
        "arch if you rush.",
        "front delts, triceps, traps",
    ),
    "Dumbbell Front Raise": (
        "Stand with dumbbells resting on your thighs, palms down. Raise one or both bells "
        "straight in front of you to shoulder height with a soft elbow, then lower slowly. "
        "Rocking back to swing the weight up is the tell that it's too heavy.",
        "upper chest, traps",
    ),
    "Cable Lateral Raise": (
        "Stand side-on to a low pulley with the handle in your far hand. Raise your arm out "
        "to shoulder height, leading with the elbow, then lower against the cable's constant "
        "tension. The cable keeps tension at the bottom where dumbbells lose it — don't rush "
        "the negative.",
        "traps",
    ),
    "Rear Delt Fly": (
        "Hinge forward until your torso is near parallel, dumbbells hanging beneath you with "
        "soft elbows. Raise the bells out to your sides, leading with the elbows and squeezing "
        "the rear delts, then lower slowly. Squeezing the shoulder blades hard together shifts "
        "the work to the mid-back — keep the motion in the shoulders.",
        "traps, upper back",
    ),
    "Face Pull": (
        "Set a rope attachment at upper-chest height and grip it thumbs-back. Pull the rope "
        "toward your face, spreading the ends apart so your hands finish beside your ears with "
        "elbows high, then return under control. Pulling to the chest with elbows down turns "
        "it into a row and skips the rear delts.",
        "traps, upper back, biceps",
    ),
    "Upright Row": (
        "Stand holding the bar at your thighs with a grip slightly narrower than shoulder "
        "width. Pull the bar up your body to chest height, leading with the elbows, then lower "
        "under control. Stop at chest height — pulling to the chin with a narrow grip is what "
        "irritates shoulders.",
        "traps, biceps",
    ),
    "Pike Push-Up": (
        "From a push-up position, walk your feet in and lift your hips high so your torso is "
        "near vertical. Lower the top of your head toward the floor between your hands, then "
        "press back up. The more vertical the torso, the more it mimics an overhead press — "
        "don't let it drift into a normal push-up.",
        "triceps, traps, upper chest",
    ),
    # ── 0009: biceps ───────────────────────────────────────────────────────
    "Hammer Curl": (
        "Stand with dumbbells at your sides, palms facing your body. Curl the bells to "
        "shoulder height keeping the neutral grip throughout, then lower to full extension. "
        "The neutral grip hits the brachialis and forearms — don't rotate the wrists mid-rep.",
        "forearms, biceps",
    ),
    "Incline Dumbbell Curl": (
        "Sit back on an incline bench with dumbbells hanging straight down behind your torso. "
        "Curl both bells up without letting the elbows drift forward, then lower to a full "
        "stretch. The incline puts the biceps on stretch — shortening the range defeats the "
        "point.",
        "forearms",
    ),
    "Preacher Curl": (
        "Set your upper arms flat on the preacher pad and grip the bar underhand. Curl to the "
        "top without lifting the elbows off the pad, then lower slowly to just short of full "
        "lockout. Dropping the weight into a slammed-out elbow at the bottom is how preacher "
        "curls get people hurt.",
        "forearms",
    ),
    "Concentration Curl": (
        "Sit with your elbow braced against your inner thigh, dumbbell hanging. Curl the bell "
        "to your shoulder with zero body movement, squeeze, and lower slowly. This is an "
        "isolation finisher — go strict and light rather than heavy and sloppy.",
        "forearms",
    ),
    # ── 0009: triceps ──────────────────────────────────────────────────────
    "Tricep Pushdown": (
        "Face a high pulley, elbows pinned to your sides, grip on the bar or rope. Push the "
        "handle down to full elbow lockout, squeeze, then let it rise until your forearms pass "
        "parallel. Letting the elbows drift forward turns it into a lat movement.",
        "forearms",
    ),
    "Overhead Cable Tricep Extension": (
        "Face away from a low pulley holding a rope overhead, elbows by your ears. Extend to "
        "full lockout in front of you, then let the rope stretch the triceps behind your head. "
        "Keep the upper arms still — only the forearms should move.",
        "shoulders, core",
    ),
    "Skull Crusher": (
        "Lie on a bench holding the bar over your shoulders with a narrow grip. Bend only at "
        "the elbows to lower the bar toward your forehead or just behind it, then extend back "
        "to lockout. Keep the upper arms vertical — letting them drift back turns it into a "
        "pullover.",
        "forearms, chest",
    ),
    "Tricep Kickback": (
        "Hinge forward with a flat back, upper arm pinned parallel to your torso, dumbbell "
        "hanging. Extend the elbow until your arm is straight behind you, squeeze, then return "
        "to 90 degrees. Swinging the upper arm is the giveaway of too much weight.",
        "rear delts",
    ),
    "Bench Dip": (
        "Place your palms on a bench behind you, legs extended in front, hips just off the "
        "edge. Lower until your elbows reach about 90 degrees, keeping them pointed straight "
        "back, then press up to lockout. Don't drift the hips away from the bench — it strains "
        "the shoulders.",
        "chest, front delts",
    ),
    # ── 0009: legs / glutes ────────────────────────────────────────────────
    "Bulgarian Split Squat": (
        "Stand a stride in front of a bench and place your rear foot on it, laces down. Lower "
        "straight down until the front thigh is parallel, keeping the front shin near "
        "vertical, then drive up through the front heel. Most people stand too close to the "
        "bench — the front knee should not shoot past the toes.",
        "glutes, hamstrings, core",
    ),
    "Walking Lunge": (
        "Holding dumbbells at your sides, step forward into a lunge until both knees reach "
        "about 90 degrees, then push off the back foot and step straight through into the "
        "next lunge. Keep the torso tall and the steps controlled — short choppy steps keep "
        "the load on the quads and knees.",
        "glutes, hamstrings, core",
    ),
    "Step-Up": (
        "Stand facing a knee-height box with dumbbells at your sides. Plant one whole foot on "
        "the box and drive through that heel to stand tall on top, then lower under control "
        "back to the floor. Pushing off the bottom leg is the classic cheat — the top leg "
        "should do the work.",
        "glutes, hamstrings, calves",
    ),
    "Hack Squat": (
        "Set your back and shoulders against the pads, feet shoulder-width on the platform. "
        "Lower under control until your knees reach at least 90 degrees, then drive up "
        "through your whole foot without locking out hard. Placing the feet too low on the "
        "platform grinds the knees — adjust until depth feels smooth.",
        "glutes, hamstrings",
    ),
    "Box Squat": (
        "Set a box at parallel height behind you and set up as for a back squat. Sit back "
        "onto the box under full control, pause briefly without relaxing, then drive up "
        "through the heels. Plopping down onto the box compresses the spine — touch it like "
        "it's hot.",
        "glutes, hamstrings, core",
    ),
    "Hip Thrust": (
        "Sit with your upper back on a bench, bar padded across your hips, feet flat and "
        "close. Drive through the heels to lift your hips until your torso is parallel to the "
        "floor, chin tucked, then lower with control. Overarching the lower back at the top "
        "instead of squeezing the glutes is the fault to avoid.",
        "hamstrings, quads, core",
    ),
    "Cable Glute Kickback": (
        "Attach a cuff to your ankle at a low pulley and hinge slightly forward holding the "
        "frame. Drive the leg straight back and up, squeezing the glute at the top, then "
        "return under control. Arching the lower back to lift the leg higher fakes range the "
        "glute isn't producing.",
        "hamstrings, core",
    ),
    # ── 0009: hamstrings ───────────────────────────────────────────────────
    "Seated Leg Curl": (
        "Sit with the pad against your lower calves and the thigh pad locked snug. Curl your "
        "heels down and under the seat as far as possible, squeeze, then return slowly to "
        "full extension. The seated position keeps the hamstrings stretched at the hip — "
        "don't scoot forward to shorten it.",
        "calves",
    ),
    "Nordic Curl": (
        "Kneel with your ankles anchored and body upright from the knees. Lower your torso "
        "toward the floor as slowly as possible using your hamstrings as brakes, catch "
        "yourself with your hands, and push back up as needed. It's brutally hard — fight for "
        "every degree of the negative rather than free-falling.",
        "glutes, calves, core",
    ),
    # ── 0009: calves ───────────────────────────────────────────────────────
    "Standing Calf Raise": (
        "Stand with the balls of your feet on the platform, heels hanging free, shoulders "
        "under the pads. Rise as high onto your toes as possible, pause at the top, then "
        "lower until you feel a full stretch in the calves. Bouncing out of the stretch turns "
        "it into a tendon exercise instead of a muscle one.",
        "soleus, hamstrings",
    ),
    "Seated Calf Raise": (
        "Sit with the pads on your knees and the balls of your feet on the platform. Press up "
        "onto your toes, pause, then lower slowly into a deep stretch. The bent knee targets "
        "the soleus, which responds to slow, full-range reps — no bouncing.",
        "gastrocnemius",
    ),
    "Dumbbell Calf Raise": (
        "Stand tall holding dumbbells, balls of your feet on a plate or step with heels "
        "hanging off. Rise onto your toes as high as possible, pause, then lower into a full "
        "stretch. Do them one leg at a time if the bells are too light to challenge you.",
        "soleus",
    ),
    # ── 0009: core / abs ───────────────────────────────────────────────────
    "Hanging Leg Raise": (
        "Hang from a pull-up bar with straight arms and a quiet body. Raise your legs — bent "
        "or straight — until your thighs pass parallel, curling the pelvis up at the top, then "
        "lower without swinging. If you start swinging between reps, pause dead before the "
        "next one.",
        "hip flexors, forearms",
    ),
    "Cable Crunch": (
        "Kneel below a high pulley holding a rope beside your head. Crunch your ribs down "
        "toward your pelvis, rounding the upper back against the cable, then return until the "
        "abs are stretched. Hinging at the hips instead of flexing the spine works the hip "
        "flexors, not the abs.",
        "obliques",
    ),
    "Russian Twist": (
        "Sit with knees bent and lean back until your abs engage, feet down or hovering. "
        "Rotate your torso side to side, moving your hands or a weight across your body with "
        "control. Whipping the arms around while the torso stays still isn't rotation — turn "
        "the shoulders.",
        "obliques, hip flexors",
    ),
    "Bicycle Crunch": (
        "Lie on your back with hands lightly behind your head, legs raised. Bring one knee "
        "toward the opposite elbow while extending the other leg, rotating through the torso, "
        "then switch sides in rhythm. Yanking on your neck is the classic fault — the hands "
        "are only a rest for the head.",
        "obliques, hip flexors",
    ),
    "Crunch": (
        "Lie on your back with knees bent and feet flat, hands across your chest or behind "
        "your head. Curl your shoulder blades off the floor by flexing the abs, pause, then "
        "lower slowly. It's a short movement — sitting all the way up brings in the hip "
        "flexors and strains the lower back.",
        "obliques",
    ),
    "Ab Wheel Rollout": (
        "Kneel with the wheel under your shoulders, hips tucked and abs braced. Roll the "
        "wheel forward as far as you can without the lower back arching, then pull it back by "
        "flexing your abs and driving the hips forward. Extend range slowly over weeks — a "
        "collapsed lower back mid-rollout is the injury to avoid.",
        "shoulders, lats, hip flexors",
    ),
}


def upgrade() -> None:
    op.add_column("exercises", sa.Column("instructions", sa.Text(), nullable=True))
    op.add_column("exercises", sa.Column("secondary_muscles", sa.String(255), nullable=True))

    conn = op.get_bind()
    for name, (instructions, secondary) in DETAILS.items():
        conn.execute(
            sa.text(
                "UPDATE exercises SET instructions = :instructions, "
                "secondary_muscles = :secondary WHERE name = :name"
            ),
            {
                "name": name,
                "instructions": instructions,
                "secondary": secondary or None,
            },
        )


def downgrade() -> None:
    op.drop_column("exercises", "secondary_muscles")
    op.drop_column("exercises", "instructions")
