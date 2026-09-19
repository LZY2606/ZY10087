package dev.railsandbox.domain;

import java.util.List;

public final class Models {
    private Models() {
    }

    public enum LinkDirection {
        FORWARD, REVERSE, BOTH
    }

    public enum SwitchPosition {
        NORMAL, REVERSE
    }

    public enum SignalAspect {
        STOP, PROCEED
    }

    public enum RouteStatus {
        IDLE, RESERVED, ACTIVE, FAULTED, RELEASED
    }

    public record Section(String id, List<String> ports) {
    }

    public record SwitchDevice(String id, String commonPort, List<String> branchPorts) {
    }

    public record SignalDevice(String id, String section) {
    }

    public record EndpointRef(String device, String port) {
        public String key() {
            return device + "#" + port;
        }
    }

    public record TrackLink(String id, EndpointRef from, EndpointRef to, LinkDirection direction) {
    }

    public record RequiredSwitch(String switchId, SwitchPosition position) {
    }

    public record RouteDefinition(
            String id,
            String entrySignal,
            List<String> sections,
            List<RequiredSwitch> explicitSwitches,
            List<RequiredSwitch> explicitFlankGuards
    ) {
    }

    public record Topology(
            String id,
            List<Section> sections,
            List<SwitchDevice> switches,
            List<SignalDevice> signals,
            List<TrackLink> links,
            List<RouteDefinition> routes
    ) {
    }

    public enum ActionType {
        REQUEST_ROUTE,
        OCCUPY_SECTION,
        RELEASE_SECTION,
        CANCEL_ROUTE,
        DEVICE_FAULT,
        DEVICE_REPAIR
    }

    public record ScenarioAction(
            String id,
            ActionType type,
            String routeId,
            String sectionId,
            String deviceId
    ) {
        public String displayName() {
            return switch (type) {
                case REQUEST_ROUTE -> "请求进路 " + routeId;
                case OCCUPY_SECTION -> "占用 " + routeId + "/" + sectionId;
                case RELEASE_SECTION -> "释放 " + routeId + "/" + sectionId;
                case CANCEL_ROUTE -> "取消进路 " + routeId;
                case DEVICE_FAULT -> "设备故障 " + deviceId;
                case DEVICE_REPAIR -> "故障恢复 " + deviceId;
            };
        }
    }

    public record ScenarioProcess(String id, List<ScenarioAction> actions) {
    }

    public record Scenario(String id, String description, List<ScenarioProcess> processes) {
    }

    public record RuleSet(
            String id,
            String name,
            boolean mutualExclusion,
            boolean flankProtection,
            boolean orderedRelease,
            boolean signalProtection
    ) {
        public static RuleSet safe(String id, String name) {
            return new RuleSet(id, name, true, true, true, true);
        }

        public static RuleSet weakFlank(String id, String name) {
            return new RuleSet(id, name, true, false, true, true);
        }

        public static RuleSet weakRelease(String id, String name) {
            return new RuleSet(id, name, true, true, false, true);
        }
    }
}
