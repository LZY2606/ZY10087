package com.railway.sandbox.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative track topology.
 *
 * Graph model: points are the nodes; sections and links are undirected edges.
 * A switch owns three port points (PLUS / STRAIGHT / MINUS). The switch
 * internally connects either PLUS-STRAIGHT (position PLUS, normal route) or
 * PLUS-MINUS (position MINUS, diverging route).
 */
public final class Topology {
    public List<Point> points = new ArrayList<>();
    public List<Switch> switches = new ArrayList<>();
    public List<Section> sections = new ArrayList<>();
    public List<Link> links = new ArrayList<>();
    public List<SignalDef> signals = new ArrayList<>();
    public List<RouteDef> routes = new ArrayList<>();
    /** Informal metadata kept for the UI. */
    public Map<String, Object> meta = new LinkedHashMap<>();

    public record Point(String id, boolean boundary, Double x, Double y) {}

    public record Switch(String id, String plus, String straight, String minus) {}

    public record Section(String id, String endA, String endB) {}

    public record Link(String id, String endA, String endB) {}

    public record SignalDef(String id, String section, String atPoint, String direction) {}

    /** Ordered list of section ids; required switch positions keyed by switch id. */
    public record RouteDef(String id, String name, String entrySignal,
                           List<String> sections, Map<String, SwitchPosition> switches) {}

    public enum SwitchPosition { PLUS, MINUS }
}
