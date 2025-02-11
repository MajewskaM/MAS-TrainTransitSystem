import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.*;

public class TransitSystemAgent extends Agent {
	
	// properties given as arguments
    private Graph<String, DefaultWeightedEdge> stationAGraph;
    private Graph<String, DefaultWeightedEdge> stationBGraph;
    private Map<String, Integer> nodesPassingTimes = new HashMap<>();
    private Map<String, List<String>> trainCurrentPaths = new HashMap<>(); // to track 'original' paths of trains
    private static Set<String> wideTunnels = new HashSet<>();
    private int trainsNumber = 0; // number of expected trains (planned with arguments)
    
    // map used to track all generated paths for trains
    private Map<String, List<List<String>>> allocatedPaths = new HashMap<>();
    
    // map to track last assigned index of path from allocatedPaths map
    private Map<String, Integer> agentPathIndex = new HashMap<>();
  
    // to track all already scheduled trains and their paths
    private Map<String, String> completedTrains = new HashMap<>();
    private boolean trainsScheduled = false; // if trains from arguments list are already scheduled
    
    // colors to color the output
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    
    @Override
    protected void setup() {
    	Object[] args = getArguments();
        if (args != null && args.length > 0) {
        	
        	// extracting the graph data
        	String graphAData = (String) args[0];
            String graphBData = (String) args[1];
            stationAGraph = createGraph(graphAData);
            stationBGraph = createGraph(graphBData);
            
            // process nodes passing times, putting info to a map
            String[] nodes = ((String) args[2]).split("\\|");
            for (String node : nodes) {
            	node = node.replaceAll("\\[", "").replaceAll("\\]", "");
                String[] splittedNode = node.split(":");
                nodesPassingTimes.put(splittedNode[0], Integer.parseInt(splittedNode[1]));
            }
            
            // process information about all trains that are at the system start
            // transit system knows all 'original' planned paths
            String[] agents = ((String) args[3]).split("\\]\\|");
            trainsNumber = agents.length;
            for (String train : agents) {
                String[] agentArgs = train.split(":\\[");
                String agentName = agentArgs[0];
                agentName = agentName.replaceAll("\\[", "").replaceAll("\\]", "");
                
                List<String> pathList = new ArrayList<>(); // list with original path
                String[] agentPath = agentArgs[1].split("\\|");
                for(String pathSegment:agentPath) {
                	String[] pathSegments = pathSegment.split(":");
                	String tunnelName = pathSegments[0];
                	pathList.add(tunnelName);
                }
                trainCurrentPaths.put(agentName, pathList);
            }
            
            // process information about wide tunnels, add them to the set
            String[] tunnels = ((String) args[4]).split("\\|");
            for (String tunnel : tunnels) {
                String tunnelName = tunnel.replaceAll("\\[", "").replaceAll("\\]", "");
                wideTunnels.add(tunnelName);
            }
            
        }
        else {
        	// without arguments agent do not know the environment
            doDelete();
        }

        addBehaviour(new HandleIncomingMessages());
        addBehaviour(new CheckSchedulingEnd());
    }
    
    // function to extract and build the graph of tunnels/platforms from string information given as the argument
    private Graph<String, DefaultWeightedEdge> createGraph(String graphString) {
    	Graph<String, DefaultWeightedEdge> graph = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        String[] edges = graphString.split("\\|");
        
        for (String edge : edges) {
        	edge = edge.replaceAll("\\[", "").replaceAll("\\]", "");
            String[] parts = edge.split(":");
            String from = parts[0];
            String to = parts[1];
            int timePassing= Integer.parseInt(parts[2]);

            graph.addVertex(from);
            graph.addVertex(to);
            DefaultWeightedEdge graphEdge = graph.addEdge(from, to);
            graph.setEdgeWeight(graphEdge, timePassing); // time to pass the rails between as an edge weight
        }

        return graph;
    }
    
    // Behavior to handle incoming messages from trains, cyclic behavior as it should respond to all messages during the runtime
    private class HandleIncomingMessages extends CyclicBehaviour {
    	private boolean considerWIDETunnels = false;
    	
        @Override
        public void action() {
        	
            ACLMessage msg = receive();
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.REQUEST) {
                	giveNewPathToTrain(msg);
                }
                else if(msg.getPerformative() == ACLMessage.INFORM) {
                	markTrainAsCompleted(msg);
                }
            } else {
                block();
            }
        }
        
        private void markTrainAsCompleted(ACLMessage msg) {
        	String trainAgent = msg.getSender().getLocalName();
            String[] content = msg.getContent().split(":");
        	String path = content[1];
        	
        	if(trainsScheduled) {
        		printSchedule();
        		System.out.println();
            	System.out.println(YELLOW + "---------------N-E-W---T-R-A-I-N-----------------" + RESET);
        		System.out.println(CYAN + "Train: " +trainAgent +" -> "+formatTimePath(path)+RESET);
            	System.out.println(YELLOW + "---------------N-E-W---T-R-A-I-N-----------------" + RESET);
            	System.out.println();
        	}
        	completedTrains.put(trainAgent, path);
        }
        
        // handle request for a path: generate all paths and/or give the sender new path from generated list
        private void giveNewPathToTrain(ACLMessage msg) {
        	String trainAgent = msg.getSender().getLocalName();
        	System.out.println(YELLOW + getLocalName()+": Received request for a PATH from " + trainAgent + RESET);
            String[] content = msg.getContent().split(":");
        	String start = content[1];
            int arrivalTime = Integer.parseInt(content[2]);
            String endNode = content[3];
            String trainSize = content[4];
            boolean noPaths = false;
            
            if(trainSize.equals("WIDE")) {
            	considerWIDETunnels = true;
            }
            
            String destinationStation;
            Graph<String, DefaultWeightedEdge> graph;
            if(start.contains("PlatformA")) {
            	graph = stationAGraph;
            	destinationStation = "PlatformB";
            }
            else {
            	graph = stationBGraph;
            	destinationStation = "PlatformA";
            }
            
            // look whether given train has already asked for a path
            int nextPathIndex = agentPathIndex.getOrDefault(trainAgent, 0);
            
            // if train asks for the first time
            if (nextPathIndex == 0) {
            	// generate all paths or maximum k given (10)
                List<List<String>> allPaths = findAllPaths(graph, start, destinationStation, 10);
                
                if (allPaths == null || allPaths.isEmpty()) {
                    noPaths = true; // no paths found
                }
                else {
                	 // paths directly reaching previously assigned platform
                    List<List<String>> directPaths = new ArrayList<>();
                    // paths arriving to other platform at destination station
                    List<List<String>> alternativePaths = new ArrayList<>();
                    
                    List<String> currentPath = trainCurrentPaths.get(trainAgent);

                    // look for direct paths (from, to platforms in original plan) minimize changes to original path
                    for (List<String> path : allPaths) {
                    	
                    	// filter out the original path from solutions
                    	if (!path.equals(currentPath)) {
                    		if (path.get(path.size() - 1).equals(destinationStation)) {
                                directPaths.add(path);
                            } else {
                                alternativePaths.add(path);
                            }
                        }
                    }

                    List<List<String>> prioritizedPaths = new ArrayList<>(directPaths);
                    prioritizedPaths.addAll(prioritizePaths(alternativePaths, start, endNode, graph));
                    
                    allocatedPaths.putIfAbsent(trainAgent, new ArrayList<>());

                    // put all paths into a map for given agent
                    for (List<String> path : prioritizedPaths) {
                    	allocatedPaths.get(trainAgent).add(path);
                    }
                    
                }
                	
            }
            
            ACLMessage reply = msg.createReply(ACLMessage.INFORM);
            String pathTransformed;
            if(noPaths || (nextPathIndex >= allocatedPaths.get(trainAgent).size())) {
                pathTransformed = "NO PATHS";
            }
            else {
                List<String> proposedPath = allocatedPaths.get(trainAgent).get(nextPathIndex);
                pathTransformed = addArrivalDepartureTimes(proposedPath, graph, arrivalTime);
            }
            
            System.out.println(YELLOW + getLocalName()+": Sending new path "+pathTransformed+" to "+trainAgent + RESET);
            reply.setContent("NEW PATH:"+pathTransformed);
            send(reply);
            nextPathIndex++;
            agentPathIndex.put(trainAgent, nextPathIndex);
            considerWIDETunnels = false;
        }
        
        
        // adding corresponding arrival and departure times to a path nodes
        private String addArrivalDepartureTimes(List<String> path, Graph<String, DefaultWeightedEdge> graph, int startTime){
        	
        	StringBuilder pathString = new StringBuilder("[");
        	int currentTime = startTime;
        	for (int i = 0; i < path.size() - 1; i++) {
        		//arrival time
        		pathString.append(path.get(i)).append(":").append(currentTime);
        		currentTime += nodesPassingTimes.get(path.get(i));
        		//departure time
        		pathString.append(":").append(currentTime).append("|");
                DefaultWeightedEdge edge = graph.getEdge(path.get(i), path.get(i + 1));
                currentTime += graph.getEdgeWeight(edge);
            }
        	pathString.append(path.get(path.size() - 1)).append(":").append(currentTime);
    		currentTime += nodesPassingTimes.get(path.get(path.size() - 1));
    		pathString.append(":").append(currentTime).append("]");
        	return pathString.toString();
        }
        
        // searching graph with the use of BFS algorithm for all or maximum K given possible paths from given platform
        private List<List<String>> findAllPaths(Graph<String, DefaultWeightedEdge> graph, String startPlatform, String endPlatformName, int k) {
            List<List<String>> paths = new ArrayList<>();
            Queue<List<String>> queue = new LinkedList<>();
            
            // adding first node
            queue.add(Collections.singletonList(startPlatform));

            // to have BFS algorithm complete with reasonable memory/time size we look at maximum for k solutions
            while (!queue.isEmpty()&& paths.size() < k) {
                List<String> currentPath = queue.poll();
                String lastNode = currentPath.get(currentPath.size() - 1);
                
                // if we found direct path to end platform (one of PlatformA's or PlatformB's), add it to solution list
                if (lastNode.contains(endPlatformName)) {
                    paths.add(new ArrayList<>(currentPath));
                    continue;
                }
                
                // add each neighbor of node to queue
                for (DefaultWeightedEdge edge : graph.outgoingEdgesOf(lastNode)) {
                    String neighbor = graph.getEdgeTarget(edge);
                    
                    // just to avoid cycles and do not go to already 'visited' nodes
                    if (!currentPath.contains(neighbor)) {
                    	if(considerWIDETunnels) {
                    		// additionally filter nodes for tunnels marked as WIDE
                    		if(wideTunnels.contains(neighbor)) {
                    			List<String> newPath = new ArrayList<>(currentPath);
                                newPath.add(neighbor);
                                queue.add(newPath);
                    		}
                    	}
                    	else {
                    		List<String> newPath = new ArrayList<>(currentPath);
                            newPath.add(neighbor);
                            queue.add(newPath);
                    	}
                        
                    }
                }
            }

            return paths;
        }
        
        // calculate the score for each path, sort them and return in descending order
        private List<List<String>> prioritizePaths(List<List<String>> paths, String start, String endNode, Graph<String, DefaultWeightedEdge> graph) {
            List<List<String>> prioritizedPaths = new ArrayList<>();
            Map<List<String>, Double> pathScores = new HashMap<>();

            // calculate scores for each path
            for (List<String> path : paths) {
            	pathScores.put(path, calculateScores(path, start, endNode, graph));
            }

            // now we can sort the paths, to give agents the best ones at the beginning (descending score order)
            List<Map.Entry<List<String>, Double>> sortedPaths = new ArrayList<>(pathScores.entrySet());
            sortedPaths.sort((p1, p2) -> Double.compare(p2.getValue(), p1.getValue()));

            for (Map.Entry<List<String>, Double> entry : sortedPaths) {
                prioritizedPaths.add(entry.getKey());
            }

            return prioritizedPaths;
        }
        
        // calculate score for each path, higher scores for starting or ending in the same node as in original plan, lower for longer traversal time
        private double calculateScores(List<String> path, String startNode, String endNode, Graph<String, DefaultWeightedEdge> graph) {
            double matchScore = 0.0;
            
            if (path.get(0).equals(startNode)) {
                matchScore += 1.0;
            }

            if (path.get(path.size() - 1).contains(endNode)) {
                matchScore += 1.0;
            }
            
            int traversalTime = 0;
            for (int i = 0; i < path.size() - 1; i++) {
            	traversalTime += nodesPassingTimes.get(path.get(i));
                DefaultWeightedEdge edge = graph.getEdge(path.get(i), path.get(i + 1));
                traversalTime += graph.getEdgeWeight(edge);
            }
            
            return matchScore / traversalTime;
        }
    }
    
    // constantly checking whether all trains have already scheduled their paths
    private class CheckSchedulingEnd extends Behaviour {
    	
    	@Override
        public void action() {
            if (completedTrains.size() == trainsNumber){
                printSchedule();
                trainsScheduled = true;
            }
        }
    	
    	@Override
    	public boolean done() {
    		return trainsScheduled;
    	}
    	
    }
    
    private void printSchedule() {
    	System.out.println();
    	System.out.println(YELLOW + "-------------------------------------------------" + RESET);
        System.out.println(GREEN + "All expected trains have completed scheduling. Planned routes:" + RESET);
        for (Map.Entry<String, String> entry : completedTrains.entrySet()) {
            System.out.println(CYAN + "Train: " + entry.getKey() + " -> " + formatTimePath(entry.getValue()));
        }
        System.out.println(YELLOW + "-------------------------------------------------" + RESET);
    }
    
    
    private String formatTimePath(String path) {
        StringBuilder formattedPath = new StringBuilder();
        String[] pathSplit = path.split(", ");

        for (String element : pathSplit) {
        	element = element.replaceAll("\\{", "");
        	element = element.replaceAll("\\}", "");
            String[] parts = element.split("=");
            String node = parts[0];
            String arrival = minutesToTime(Integer.parseInt(parts[1]));
            String departure = minutesToTime(Integer.parseInt(parts[2]));
            
            formattedPath.append(node).append(" [").append(arrival).append(" - ").append(departure).append("], ");
        }
        
        formattedPath.setLength(formattedPath.length() - 2);
        return formattedPath.toString();
    }
    
    private String minutesToTime(int minutes) {
        int hours = minutes / 60;
        int mins = minutes % 60;
        return String.format("%02d:%02d", hours, mins);
    }
    
    
    protected void takeDown() {
    System.out.println(RED + "TransitSystemAgent "+getLocalName()+" terminating");
    }
}
