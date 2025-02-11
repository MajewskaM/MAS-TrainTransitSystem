import jade.Boot;

import java.io.*;
import java.util.*;

public class TrainTransitSystem {
	private static Map<String, Integer> faultyPlatforms = new HashMap<>();
	private static Set<String> wideTunnels = new HashSet<>();

    public static void main(String[] args) {
 
    	// load data graph, tunnels, agents configurations
        Properties config = loadSystemConfig("config.properties");
        
        List<String> graphA = Arrays.asList(config.getProperty("graphA").split(";"));
        List<String> graphB = Arrays.asList(config.getProperty("graphB").split(";"));
        
        Map<String, Integer> passingTimes = transformNodesTimes(config.getProperty("passingTimes"));
        Map<String, String> trainArgs = transformTrainArgs(config);

        // generate planned time slots for all trains in given nodes (static allocation plans)
        Map<String, String> allPlannedPaths = generateAllPlannedPaths(trainArgs, graphA, graphB, passingTimes);

        String arguments = prepareJadeArgs(graphA, graphB, passingTimes, trainArgs, allPlannedPaths);
        System.out.println("JADE Arguments: " + arguments);

        // pass arguments to JADE and launch it
        Boot.main(new String[]{"-gui", arguments});
    }
    
    // loading properties from file, catching possible exceptions
    private static Properties loadSystemConfig(String fileName) {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(fileName)) {
            properties.load(input);
        } catch (IOException e) {
            e.printStackTrace();
            // if there is an exception exit system
            System.exit(1);
        }
        return properties;
    }
    
    // transforming nodes times into key value map
    private static Map<String, Integer> transformNodesTimes(String allNodesTimes) {
        Map<String, Integer> nodesTimes = new LinkedHashMap<>();
        String[] nodes = allNodesTimes.split(";");
        for (String node : nodes) {
            String[] splittedNode = node.split(",");
            if(node.contains("FAULTY")) {
            	faultyPlatforms.put(splittedNode[0], timeToMinutes(splittedNode[4]));
            	
            }
            if (node.contains("WIDE")) {
                wideTunnels.add(splittedNode[0]);
            }
            
            nodesTimes.put(splittedNode[0], Integer.parseInt(splittedNode[1]));
        }
        return nodesTimes;
    }
    
    // transforming given train arguments into a map, same as for nodes times
    private static Map<String, String> transformTrainArgs(Properties config) {
        Map<String, String> trainArgs = new LinkedHashMap<>();
        for (String key : config.stringPropertyNames()) {
        	// look for configuration for each agent
            if (key.startsWith("Train")) {
            	trainArgs.put(key, config.getProperty(key));
            }
        }
        return trainArgs;
    }
    
    // given the static plan of traversal between two stations and start time
    // generate arrival and departure times with accordance to graph traversal times
    private static Map<String, String> generateAllPlannedPaths(Map<String, String> trainArgs, 
                                                               List<String> graphA,List<String> graphB, 
                                                               Map<String, Integer> nodesTimes) 
    {
    	// for each train we need to generate new string that will include traversal time
        Map<String, String> plannedPaths = new LinkedHashMap<>();
        for (String train : trainArgs.keySet()) {
            String args = trainArgs.get(train);
            plannedPaths.put(train, generatePlannedPath(args, graphA, graphB, nodesTimes));
        }
        return plannedPaths;
    }
    
    // generating minutes of arrival/departure at each station mentioned in arguments
    private static String generatePlannedPath(String args, List<String> graphA,List<String> graphB, Map<String, Integer> nodesTimes) {
        String[] trainSplit = args.split("],");
        String[] path = trainSplit[0].replaceAll("\\[", "").split(",");
        String[] argsSplit = trainSplit[1].split(",");
        String startTime = argsSplit[0];
        String startPoint = path[0];
        String endPoint = path[path.length - 1];
        
        // we rely on time stamp in minutes
        int currentTime = timeToMinutes(startTime);
        StringBuilder pathBuilder = new StringBuilder("[");
        pathBuilder.append(startPoint + "Agent").append(":")
    	.append(currentTime).append(":")
    	.append(currentTime+nodesTimes.get(startPoint)).append("|");
        
        currentTime+=nodesTimes.get(startPoint);
        
        List<String> graph = graphB;
        
        // choose graph according to start station
        if(startPoint.contains("A")) {
        	graph = graphA;
        }
        
		// calculate times of arrival and departure for each tunnel
		for (int i = 1; i < path.length - 1; i++) {
			
	    	// we can assume that tunnels connections specified in the config file are VALID with the provided graph
	        for (String edge : graph) {
	        	
	            String[] edgeParts = edge.split(",");
	            String from = edgeParts[0];
	            String to = edgeParts[1];
	            
	            if (from.equals(startPoint) && to.equals(path[i])) {
	                
	            	int travelTime = Integer.parseInt(edgeParts[2]);
	            	currentTime += travelTime;
	
	                pathBuilder.append(path[i] + "Agent").append(":")
	                           .append(currentTime).append(":")
	                           .append(currentTime + nodesTimes.get(path[i])).append("|");
	
	                currentTime += nodesTimes.get(path[i]);
	                startPoint = path[i];
	                break;
	               
	            }
	        }
	    }

	    // look for the edge connecting last tunnel with end station
	    for (String edge : graph) {
	    	
	        String[] edgeParts = edge.split(",");
	        String from = edgeParts[0];
	        String to = edgeParts[1];

	        // we assume that each rail can be traversed in both directions
	        if (from.equals(startPoint) && to.equals(endPoint)) {
	        	int travelTime = Integer.parseInt(edgeParts[2]);
	        	currentTime += travelTime;
	
	            pathBuilder.append(endPoint + "Agent").append(":")
	                       .append(currentTime).append(":")
	                       .append(currentTime+ nodesTimes.get(endPoint)).append("]");
	
	            break;
	        }
	    }

        return pathBuilder.toString();
    }
    
    // converting time to minutes
    private static int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    // building a string consisting of all previously generated structures, adding necessary arguments to each agent
    private static String prepareJadeArgs(List<String> graphA,List<String> graphB, Map<String, Integer> passingTimes, 
    									  Map<String, String> trainArgs, 
    									  Map<String, String> allPlannedPaths
    									  ) {
    	String graphAString = updateGraphData(graphA);
    	String graphBString = updateGraphData(graphB);
    	
    	StringBuilder args = new StringBuilder();
    	args.append("TransitSystemAgent:TransitSystemAgent(")
    	.append("[").append(graphAString).append("],[").append(graphBString).append("],");
    	 
    	args.append("[");
    	for (Map.Entry<String, Integer> entry : passingTimes.entrySet()) {
    		args.append(entry.getKey()+"Agent").append(":").append(entry.getValue()).append("|");
    	}
    	args.setLength(args.length() - 1);
    	args.append("],[");
    	for (String train : trainArgs.keySet()) {
    		   args.append(train).append(":")
                   .append(allPlannedPaths.get(train)).append("|");
    	}
    	args.setLength(args.length() - 1);
    	args.append("],[");
    	for (String tunnel : wideTunnels) {
    		   args.append(tunnel).append("|");
    	}
    	args.setLength(args.length() - 1);
    	args.append("]);");
    	
    	//we need to identify occupied platforms 
        for (Map.Entry<String, Integer> entry : passingTimes.entrySet()) {
            String key = entry.getKey();
            List<String> directedNeighbors = getNeighbors(key, graphA, graphB);
            
            args.append(key).append("Agent:TunnelAgent(").append("[")
            .append(String.join("|", directedNeighbors)+"]");
            
            // we need to identify nodes out of order
        	if (faultyPlatforms.containsKey(key)) {
        		args.append(",FAULTY:")
        		.append(faultyPlatforms.get(key)); // + info when it will be free again
        	}
        	else {
        		args.append(",FREE");
        	}
        	args.append(");");
        	
        }
        
        for (String train : trainArgs.keySet()) {
        		String allArgs = trainArgs.get(train);
                
                
	        	String[] trainSplit = allArgs.split("],");
	            String[] argsSplit = trainSplit[1].split(",");
        		   args.append(train).append(":TrainAgent(")
                       .append(argsSplit[1]).append(",")
                       .append(argsSplit[2]).append(",")
                       .append(allPlannedPaths.get(train)).append(");");
        }
        return args.toString();
    }
    
    // transform graph into string identifying responsible agents for the nodes
    private static String updateGraphData(List<String> graph) {
        StringBuilder updatedGraph = new StringBuilder();

        for (String edgeInfo : graph) {
            String[] edge = edgeInfo.split(",");
            String newEdge1 = edge[0] + "Agent";
            String newEdge2 = edge[1] + "Agent";
            
            updatedGraph.append(newEdge1).append(":")
            .append(newEdge2).append(":").append(edge[2]).append("|");
        }
        updatedGraph.setLength(updatedGraph.length() - 1);
        return updatedGraph.toString();
    }
    
    // find neighbor incoming graph edges, we distinguish edges from A and B
    private static List<String> getNeighbors(String node, List<String> graphA, List<String> graphB) {
        List<String> neighbors = new ArrayList<>();

        // neighbors from graphA
        for (String edge : graphA) {
            String[] edgeParts = edge.split(",");
            String from = edgeParts[0];
            String to = edgeParts[1];
            int travelTime = Integer.parseInt(edgeParts[2]);

            if (to.equals(node)) {
                neighbors.add("A:" + from + ":" + travelTime);
            }
        }

        // neighbors from graphB
        for (String edge : graphB) {
            String[] edgeParts = edge.split(",");
            String from = edgeParts[0];
            String to = edgeParts[1];
            int travelTime = Integer.parseInt(edgeParts[2]);

            if (to.equals(node)) {
                neighbors.add("B:" + from + ":" + travelTime);
            }
        }

        return neighbors;
    }


    
    
}