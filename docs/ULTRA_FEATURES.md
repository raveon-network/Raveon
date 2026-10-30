# Ultra feature protocol

Ultra requests use GAIQ protocol version 3, model ID 2, 64 frames and 152 float32 values per frame. Pro is unchanged: protocol version 2, model ID 1 and 8 values per frame.

The FlatBuffers table retains its five slots: player name (0), frame count (1), flattened row-major float vector (2), model ID (3), protocol version (4). Batch framing and GAIB/RVM2 responses are unchanged.

CSV contains is_cheating followed by exactly the same 152 feature values in exactly the same order as the API. Parsing a CSV feature as float32 reproduces its wire value. The original 24 dataset columns keep six decimal places; appended columns use round-trip float formatting. The label is never transmitted to inference.

## Backend integration

For Ultra, validate version 3 and width 152 before reshaping to [frame_count, 152]. All columns 0 through 151 are training inputs, in the order listed below and in ultra-schema.json. Use the same feature order for training and inference. Handle unavailable values consistently during preprocessing.

No backend changes are included in this repository. Do not deploy this plugin against a backend that still assumes a 23-column Ultra payload. Pro requests remain compatible with their existing decoder.

The 128 appended columns use descriptive names. Columns that would repeat an existing context name use the telemetry_ prefix to keep CSV names unique. The payload uses float32 values, including coordinates and large counters.

## Measurement semantics and availability

Collection retains Raveon's rotation-frame cadence and combat/dataset gates. The counters advance on accepted movement packets; transient events accumulate between emitted rotation frames. dt_ms measures arrival time between emitted frames, capped at 10000 ms. sequence_id changes on state resets. Future attack-relative offsets are not available in this streaming format.

UNAVAILABLE is the finite float32 value -3.4028235e38 (negative Float.MAX_VALUE). Treat it as missing telemetry, never as a model input, probability or physical measurement. It is deliberately different from valid zero values and from categorical -1 sentinels.

- Player position and rotation originate from accepted movement packets. Input flags originate from PLAYER_INPUT on supported clients; older clients or clients without such a packet have input_valid=0 and unavailable input flags.
- Movement flags, pose, health, food, attributes, effects and inventory use the player entity thread. Absent modern attributes use the corresponding vanilla defaults on older server versions.
- Damage is the final non-cancelled Bukkit damage event. Velocity and explosion vectors come from outgoing packets; they describe sent updates, not client acknowledgement.
- Item-use and placement fields describe observed input and server item state. Placement fields do not assert a successful block placement. Ground/block friction and stuck multipliers describe local server block context; they are not a complete movement simulation.
- Targets and neighbors use server snapshots. Regional snapshots may be up to 75 ms old. Missing or stale snapshots produce missing neighbor counts and an invalid target. Neighbor collection is limited to eight blocks; target and hittable counts use six blocks.
- Aim mode and delta-dot fields reuse the existing Ultra calculation. Geometric aim, distance and ray fields use server bounding boxes and bounded calculations. raytrace_hit and raytrace_hit_strict use the same current box. These fields are not latency-compensated measurements.
- box_inflation is unavailable because Raveon does not maintain a latency-compensated union box. ticks_to_attack is 0 on an observed attack and unavailable otherwise; future attack-relative offsets cannot be obtained from this streaming collection.
- crystal_spawn_to_attack uses server entity age on a crystal attack, not the time since the client received a spawn packet.
- active_firework_count counts nearby fireworks attached to the player when that API is available.
- Occlusion/exposure only read already loaded blocks owned by the current region. Incomplete access produces unavailable values. Geometric calculations run at most once per 25 ms of packet arrival time per player; excess frames carry unavailable geometry while their other fields remain available.
- Packet work uses the existing bounded queue. Overflow/reload/teleport resets state. Telemetry is collected only when Ultra analysis or Ultra dataset collection is requested.

Boolean fields use 0/1 when available. Effect amplifiers use -1 for no effect. Missing place face/cursor, absent anchor charge/interval and absent crystal attack use -1. target_type uses 0 player, 1 other living, 2 crystal, 3 vehicle, 4 other and 5 unknown. Item classes use 0 unknown, 1 empty, 2 melee, 3 crystal, 4 crystal base, 5 anchor, 6 glowstone, 7 totem, 8 block, 9 ranged, 10 projectile, 11 consumable and 12 shield.

## Collection access

Dataset permissions control who may start and stop datacollect. The selected player does not need that permission. CSV is written only while a dataset session is active for that player and its combat gate is open.

Normal Ultra inference also includes telemetry while enabled and analyzing combat, regardless of dataset-command permissions. Outside an explicit dataset session those inference rows are not written to CSV. Packet-state tracking remains active while Ultra is requested.

Collection observes existing packets and server state; it does not send extra packets to the game client. It adds server work and backend traffic. Its impact on tick time depends on player count and combat activity and has not been established by a production load test.

## Payload size

A 64-frame Ultra vector is 38912 bytes before FlatBuffers/batch overhead, compared with 5888 bytes for the previous 23-column vector (about 6.61 times larger). A batch of 32 Ultra windows contains about 1.25 MB of vector data. Queue and batch-count limits remain in effect.

## Columns

Indices below refer to the API vector; CSV adds the label at column zero.

| Index | Name | Training input |
| --- | --- | --- |
| 0 | delta_yaw | yes |
| 1 | delta_pitch | yes |
| 2 | accel_yaw | yes |
| 3 | accel_pitch | yes |
| 4 | jerk_yaw | yes |
| 5 | jerk_pitch | yes |
| 6 | gcd_error_yaw | yes |
| 7 | gcd_error_pitch | yes |
| 8 | rotation_present | yes |
| 9 | pitch | yes |
| 10 | mode_yaw | yes |
| 11 | mode_pitch | yes |
| 12 | mode_yaw_valid | yes |
| 13 | mode_pitch_valid | yes |
| 14 | delta_dots_yaw | yes |
| 15 | delta_dots_pitch | yes |
| 16 | target_valid | yes |
| 17 | target_aim_angle | yes |
| 18 | target_rel_x | yes |
| 19 | target_rel_y | yes |
| 20 | target_rel_z | yes |
| 21 | targets_in_range_count | yes |
| 22 | held_item_class | yes |
| 23 | player_ping | yes |
| 24 | sequence_id | yes |
| 25 | tick_index | yes |
| 26 | dt_ms | yes |
| 27 | target_entity_id | yes |
| 28 | yaw | yes |
| 29 | telemetry_pitch | yes |
| 30 | x | yes |
| 31 | y | yes |
| 32 | z | yes |
| 33 | on_ground | yes |
| 34 | sprinting | yes |
| 35 | sneaking | yes |
| 36 | active_item | yes |
| 37 | flying | yes |
| 38 | swimming | yes |
| 39 | gliding | yes |
| 40 | in_vehicle | yes |
| 41 | is_climbing | yes |
| 42 | in_water | yes |
| 43 | pose | yes |
| 44 | input_forward | yes |
| 45 | input_backward | yes |
| 46 | input_left | yes |
| 47 | input_right | yes |
| 48 | input_jump | yes |
| 49 | input_shift | yes |
| 50 | input_sprint | yes |
| 51 | game_mode | yes |
| 52 | food_level | yes |
| 53 | ground_friction | yes |
| 54 | fall_distance | yes |
| 55 | movement_speed | yes |
| 56 | jump_amplifier | yes |
| 57 | slow_falling | yes |
| 58 | telemetry_player_ping | yes |
| 59 | speed_amplifier | yes |
| 60 | slowness_amplifier | yes |
| 61 | haste_amplifier | yes |
| 62 | mining_fatigue_amplifier | yes |
| 63 | entity_interaction_range | yes |
| 64 | scale | yes |
| 65 | gravity | yes |
| 66 | jump_strength | yes |
| 67 | step_height | yes |
| 68 | telemetry_target_valid | yes |
| 69 | target_distance | yes |
| 70 | telemetry_target_aim_angle | yes |
| 71 | telemetry_target_rel_x | yes |
| 72 | telemetry_target_rel_y | yes |
| 73 | telemetry_target_rel_z | yes |
| 74 | ticks_to_attack | yes |
| 75 | attack_this_tick | yes |
| 76 | aim_error | yes |
| 77 | same_target_as_prev_attack | yes |
| 78 | telemetry_targets_in_range_count | yes |
| 79 | is_critical | yes |
| 80 | raytrace_hit | yes |
| 81 | attack_ray_occluded | yes |
| 82 | raytrace_miss_distance | yes |
| 83 | input_valid | yes |
| 84 | hit_point_rel_x | yes |
| 85 | hit_point_rel_y | yes |
| 86 | hit_point_rel_z | yes |
| 87 | ticks_since_attack | yes |
| 88 | swung_this_tick | yes |
| 89 | attack_cooldown_progress | yes |
| 90 | attack_speed | yes |
| 91 | is_sprinting_on_attack | yes |
| 92 | received_velocity_this_tick | yes |
| 93 | velocity_x | yes |
| 94 | velocity_y | yes |
| 95 | velocity_z | yes |
| 96 | ticks_since_velocity | yes |
| 97 | received_explosion_this_tick | yes |
| 98 | ticks_since_explosion | yes |
| 99 | ticks_since_use_item | yes |
| 100 | telemetry_rotation_present | yes |
| 101 | telemetry_mode_yaw | yes |
| 102 | telemetry_mode_pitch | yes |
| 103 | telemetry_mode_yaw_valid | yes |
| 104 | telemetry_mode_pitch_valid | yes |
| 105 | telemetry_delta_dots_yaw | yes |
| 106 | telemetry_delta_dots_pitch | yes |
| 107 | active_firework_count | yes |
| 108 | stuck_mult_x | yes |
| 109 | stuck_mult_y | yes |
| 110 | stuck_mult_z | yes |
| 111 | on_slime | yes |
| 112 | on_honey | yes |
| 113 | on_soul_sand | yes |
| 114 | on_mud | yes |
| 115 | levitation_amplifier | yes |
| 116 | swim_friction | yes |
| 117 | riptide_active | yes |
| 118 | teleport_tick | yes |
| 119 | exposure_fraction | yes |
| 120 | box_inflation | yes |
| 121 | health | yes |
| 122 | damage_taken_this_tick | yes |
| 123 | ticks_since_damage | yes |
| 124 | window_start | yes |
| 125 | target_type | yes |
| 126 | crystal_spawn_to_attack | yes |
| 127 | use_offhand | yes |
| 128 | nearby_crystal_count | yes |
| 129 | anchor_charge | yes |
| 130 | anchor_use_interval | yes |
| 131 | place_face | yes |
| 132 | place_block_class | yes |
| 133 | place_block_x | yes |
| 134 | place_block_y | yes |
| 135 | place_block_z | yes |
| 136 | place_cursor_x | yes |
| 137 | place_cursor_y | yes |
| 138 | place_cursor_z | yes |
| 139 | place_inside_block | yes |
| 140 | places_this_tick | yes |
| 141 | telemetry_held_item_class | yes |
| 142 | offhand_item_class | yes |
| 143 | slot_switches_this_tick | yes |
| 144 | explosion_kb_x | yes |
| 145 | explosion_kb_y | yes |
| 146 | explosion_kb_z | yes |
| 147 | hittable_entities_count | yes |
| 148 | aim_error_yaw | yes |
| 149 | aim_error_pitch | yes |
| 150 | raytrace_dirs_hit_count | yes |
| 151 | raytrace_hit_strict | yes |
