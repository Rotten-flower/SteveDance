package dev.flower.stevedance.planet;

final class GameModules {
    static void define(Values.Scope e, String name) {
        switch (name) {
            case "behavior" -> Behavior.define(e);
            case "enemy_runtime" -> EnemyRuntime.define(e);
            case "gimmick_runtime" -> GimmickRuntime.define(e);
            case "map_runtime" -> MapRuntime.define(e);
            case "player_animation" -> PlayerAnimation.define(e);
            case "player_camera" -> PlayerCamera.define(e);
            case "player_collision" -> PlayerCollision.define(e);
            case "player_effects" -> PlayerEffects.define(e);
            case "player_interactions" -> PlayerInteractions.define(e);
            case "player_items" -> PlayerItems.define(e);
            case "player_kinematics" -> PlayerKinematics.define(e);
            case "player_motion" -> PlayerMotion.define(e);
            case "player_runtime" -> PlayerRuntime.define(e);
            case "player_stats" -> PlayerStats.define(e);
            case "sprite_geometry" -> SpriteGeometry.define(e);
            case "world_runtime" -> WorldRuntime.define(e);
            case "preview_content" -> PreviewContent.define(e);
            case "preview_quiz" -> PreviewQuiz.define(e);
            case "preview_session" -> PreviewSession.define(e);
            case "preview_runtime" -> PreviewRuntime.define(e);
        }
    }

    static void link(Values.Scope e, String name) {
        switch (name) {
            case "behavior" -> Behavior.link(e);
            case "enemy_runtime" -> EnemyRuntime.link(e);
            case "gimmick_runtime" -> GimmickRuntime.link(e);
            case "map_runtime" -> MapRuntime.link(e);
            case "player_animation" -> PlayerAnimation.link(e);
            case "player_camera" -> PlayerCamera.link(e);
            case "player_collision" -> PlayerCollision.link(e);
            case "player_effects" -> PlayerEffects.link(e);
            case "player_interactions" -> PlayerInteractions.link(e);
            case "player_items" -> PlayerItems.link(e);
            case "player_kinematics" -> PlayerKinematics.link(e);
            case "player_motion" -> PlayerMotion.link(e);
            case "player_runtime" -> PlayerRuntime.link(e);
            case "player_stats" -> PlayerStats.link(e);
            case "sprite_geometry" -> SpriteGeometry.link(e);
            case "world_runtime" -> WorldRuntime.link(e);
            case "preview_content" -> PreviewContent.link(e);
            case "preview_quiz" -> PreviewQuiz.link(e);
            case "preview_session" -> PreviewSession.link(e);
            case "preview_runtime" -> PreviewRuntime.link(e);
        }
    }
}
