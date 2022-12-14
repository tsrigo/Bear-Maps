import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.*;


/**
 * Parses OSM XML files using an XML SAX parser. Used to construct the graph of roads for
 * pathfinding, under some constraints.
 * See OSM documentation on
 * <a href="http://wiki.openstreetmap.org/wiki/Key:highway">the highway tag</a>,
 * <a href="http://wiki.openstreetmap.org/wiki/Way">the way XML element</a>,
 * <a href="http://wiki.openstreetmap.org/wiki/Node">the node XML element</a>,
 * and the java
 * <a href="https://docs.oracle.com/javase/tutorial/jaxp/sax/parsing.html">SAX parser tutorial</a>.
 * <p>
 * You may find the CSCourseGraphDB and CSCourseGraphDBHandler examples useful.
 * <p>
 * The idea here is that some external library is going to walk through the XML
 * file, and your override method tells Java what to do every time it gets to the next
 * element in the file. This is a very common but strange-when-you-first-see it pattern.
 * It is similar to the Visitor pattern we discussed for graphs.
 *
 * @author Alan Yao, Maurice Lee
 */
public class GraphBuildingHandler extends DefaultHandler {
    /**
     * Only allow for non-service roads; this prevents going on pedestrian streets as much as
     * possible. Note that in Berkeley, many of the campus roads are tagged as motor vehicle
     * roads, but in practice we walk all over them with such impunity that we forget cars can
     * actually drive on them.
     */
    private static final Set<String> ALLOWED_HIGHWAY_TYPES = new HashSet<>(Arrays.asList
            ("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
                    "residential", "living_street", "motorway_link", "trunk_link", "primary_link",
                    "secondary_link", "tertiary_link"));
    /**
     * 表示目前解析的对象是node还是way
     */
    private String activeState = "";
    /**
     * 用来记录目前解析的Way的id
     */
    private long activeWay = -1;
    /**
     * 用来存储节点，边，路等信息的图
     */
    private final GraphDB g;
//    /**
//     * 用来记录当前道路是否属于 ALLOWED_HIGHWAY_TYPES
//     */
//    private boolean flag = false;

    /**
     * Create a new GraphBuildingHandler.
     *
     * @param g The graph to populate with the XML data.
     */
    public GraphBuildingHandler(GraphDB g) {
        this.g = g;
    }

    /**
     * 用来暂存可能成为路的节点
     */
    Queue<Long> poss = new LinkedList<>();
    /**
     * 记录最后一次处理的节点（是不断更新的）
     */
    long last = 0;

    /**
     * Called at the beginning of an element. Typically, you will want to handle each element in
     * here, and you may want to track the parent element.
     *
     * @param uri        The Namespace URI, or the empty string if the element has no Namespace URI or
     *                   if Namespace processing is not being performed.
     * @param localName  The local name (without prefix), or the empty string if Namespace
     *                   processing is not being performed.
     * @param qName      The qualified name (with prefix), or the empty string if qualified names are
     *                   not available. This tells us which element we're looking at.
     * @param attributes The attributes attached to the element. If there are no attributes, it
     *                   shall be an empty Attributes object.
     * @throws SAXException Any SAX exception, possibly wrapping another exception.
     * @see Attributes
     */
    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {

        if (qName.equals("node")) { /* 建图，并向里面添加节点 */
            /* We encountered a new <node...> tag. */
            activeState = "node";

            long id = Long.parseLong(attributes.getValue("id"));
            double lon = Double.parseDouble(attributes.getValue("lon"));
            double lat = Double.parseDouble(attributes.getValue("lat"));

            g.addNode(id, lat, lon);
            last = id;
        }
        else if (qName.equals("way")) { /*遇到了路，首先是记录id*/
            /* We encountered a new <way...> tag. */
            activeState = "way";
            long wayId = Long.parseLong(attributes.getValue("id"));
            activeWay = wayId;
            g.addWay(wayId);
        }
        else if (activeState.equals("way") && qName.equals("nd")) { /*处理Way的节点，应该把节点添加到poss里面*/
            poss.add(Long.parseLong(attributes.getValue("ref")));
        }
        else if (activeState.equals("way") && qName.equals("tag")) { /*处理Way的tag，应该把tag添加到对象里*/
            /* While looking at a way, we found a <tag...> tag. */
            String k = attributes.getValue("k");
            String v = attributes.getValue("v");

            switch (k) {
                case "maxspeed":
                    g.addWaytag(activeWay, "maxspeed", v);
                    break;
                case "highway":
                    g.addWaytag(activeWay, "highway", v);
//                if (ALLOWED_HIGHWAY_TYPES.contains(v)) {
//                    flag = true;
//                }
                    break;
                case "name":
                    g.addWaytag(activeWay, "name", v);
                    break;
            }
        }
        else if (activeState.equals("node") && qName.equals("tag") && attributes.getValue("k")
                .equals("name")) {
            /* While looking at a node, we found a <tag...> with k="name". */
            if (last == 0) {
                throw new IllegalAccessError("last is 0!");
            }
            g.setTag(last, "name", attributes.getValue("v"));
        }
    }

    /**
     * Receive notification of the end of an element. You may want to take specific terminating
     * actions here, like finalizing vertices or edges found.
     *
     * @param uri       The Namespace URI, or the empty string if the element has no Namespace URI or
     *                  if Namespace processing is not being performed.
     * @param localName The local name (without prefix), or the empty string if Namespace
     *                  processing is not being performed.
     * @param qName     The qualified name (with prefix), or the empty string if qualified names are
     *                  not available.
     * @throws SAXException Any SAX exception, possibly wrapping another exception.
     */
    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (qName.equals("way")) {
            if (g.getWaytag(activeWay, "highway") == null || !ALLOWED_HIGHWAY_TYPES.contains(g.getWaytag(activeWay, "highway"))){
                poss.clear();   // 这一步至关重要。BUG: 忘记重置 poss
                return;
            }
            if (poss.isEmpty()){
                throw new IllegalAccessError("poss is empty! please check!");
            }
            String wayName = g.getWaytag(activeWay, "name");
            long st = poss.poll();
            while (!poss.isEmpty()){
                if (g.getTag(st).get("name") == null)
                    g.setTag(st, "name", wayName);
                long ne = poss.poll();
                g.addEdge(st, ne);
                g.addEdge(ne, st);
                st = ne;
            }
        }
    }
}
