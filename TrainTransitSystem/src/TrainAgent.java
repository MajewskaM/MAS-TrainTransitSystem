import jade.core.Agent;

import jade.core.behaviours.Behaviour;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;

import jade.lang.acl.ACLMessage;
import jade.core.AID;

public class TrainAgent extends Agent {
	
	// properties given as the arguments to train
	// node at which the train starts
    private String startNode = null;
    private String trainSize;
    private int maxDelay;
    private Map<String, SimpleEntry<Integer, Integer>> plannedPath;
    
    // colors to color the output
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String CYAN = "\u001B[36m";
    
    @Override
    protected void setup() {

        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            
            trainSize = args[0].toString();
            maxDelay = Integer.parseInt(args[1].toString());
            String plannedPathRaw = args[2].toString();
            
            //transform path from argument to a map
            plannedPathRaw = plannedPathRaw.replaceAll("\\[", "").replaceAll("\\]", "");
            plannedPath = transformRawPath(plannedPathRaw);
            
        }
        else {
       	 // without arguments make the train terminate immediately
       	 doDelete();
       }
        
        addBehaviour(new ReservePath());
    }	
    
    // transforming path given as a string as an argument to a map
    private Map<String, SimpleEntry<Integer, Integer>> transformRawPath(String plannedPathRaw) {
    	List<String> nodes = Arrays.asList(plannedPathRaw.split("\\|"));
    	Map<String, SimpleEntry<Integer, Integer>> transformedPath = new LinkedHashMap<>();
        
        for (String node : nodes) {
            String[] nodeSplit = node.split(":");
            String agentName = nodeSplit[0];
            int arrivalTime = Integer.parseInt(nodeSplit[1].toString());
            int departureTime = Integer.parseInt(nodeSplit[2].toString());
            transformedPath.put(agentName, new SimpleEntry<>(arrivalTime, departureTime));
            if (startNode == null) {
            	startNode = agentName;
            }
        }
        return transformedPath;
    }
    
    // trying to reserve its original or alternative path in a network of tunnels (graph)
    private class ReservePath extends Behaviour {
        private boolean currentPathConfirmed = false;
        private boolean forcedReservation = false;
        private int currentStep = 0; // steps are different action taken by agent at a given time
        
        // segments of first planned path
        List<Map.Entry<String, SimpleEntry<Integer, Integer>>> pathSegmentsPlanned = new ArrayList<>(plannedPath.entrySet());
        
        // segments of current path - assigned during each reservation attempt
        List<Map.Entry<String, SimpleEntry<Integer, Integer>>> pathSegments;
        
        // train at first takes as a current path its planned path, and will try to reserve it
        Map<String, SimpleEntry<Integer, Integer>> currentPath = plannedPath;
        
        // to keep all paths with delay
        Map<Integer, Map<String, SimpleEntry<Integer, Integer>>> delayedPaths = new LinkedHashMap<>();
        
        // to keep track of requests sent by agent
        List<String> sentRequests;
        
        @Override
        public void action() {
        	
        	switch(currentStep) {
        	case 0: //send request to all nodes for current path
        		sendRequestForPath();
        		break;
        		
        	case 1: // waiting for all tunnel responses with respect to some TIMEOUT
        		waitAndAnalyseTrainResponses();
                break;
        	case 2: 
                // sending confirmation to all nodes
                sendConfirmations();
                currentStep = 5;
        		break;
        	case 3: 
        		// send request to generate new path
        		requestNewPath();
        		currentStep = 4;
        		break;
        	case 4:
        		// decide what to do with a new path
        		receiveNewPath();
        		break;
        	case 5:
                // end the behavior
        		informTransitSystem();
                currentPathConfirmed = true;
                break;
        	}
            
    	}
        
        
        // sending request for a given time interval to each node this path includes
        private void sendRequestForPath() {
        	System.out.println(CYAN+getLocalName() + ": Trying to reserve its current path: " + currentPath+RESET);

            // track sent reservation request to each tunnel in path
            sentRequests = new ArrayList<>();
            pathSegments = new ArrayList<>(currentPath.entrySet());
            
            Map.Entry<String, SimpleEntry<Integer, Integer>> segmentFirst = pathSegments.get(0);
        	String startingNode = segmentFirst.getKey();
        	
            String currentNode = startingNode;
            
            //let the agents think for a while
            block(1000);
            // for each node in path send reservation request
            for (int i = 0; i < pathSegments.size(); i++) {
            	Map.Entry<String, SimpleEntry<Integer, Integer>> segment = pathSegments.get(i);
            	
            	String node = segment.getKey();
                int arrival = segment.getValue().getKey();
                int departure = segment.getValue().getValue();
                
                ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                request.addReceiver(getAID(node));
                request.setContent("RESERVE:"+startingNode+":"+currentNode+":"+arrival+":"+departure);
                send(request);

                // store request in train brain with waiting state
                sentRequests.add(node);
                currentNode = node;
            }
            currentStep = 1;
        }
        
        // wait with respect to specific timeout for the responses of the tunnels, after timeout analyse received responses
        private void waitAndAnalyseTrainResponses() {
        	Map<String, String> requestResponses = new HashMap<>();
    		
            // if at least one requests was rejected
            boolean someRequestRejected = false;
            
            long startTime = System.currentTimeMillis();
            long timeout = 5000; // we wait 5 seconds for the responses
            System.out.println(CYAN+getLocalName() + ": START TIMEOUT for tunnel responses"+RESET);
            
            while (requestResponses.size() < sentRequests.size()) {
            	long elapsedTime = System.currentTimeMillis() - startTime;
            	
                if (elapsedTime >= timeout) {
                    break;
                }
                
            	ACLMessage response = receive();
                if (response != null) {
                	String tunnel = response.getSender().getLocalName();
                    String responseContent = response.getContent();
                    
                	if(response.getPerformative() == ACLMessage.AGREE) {
                		requestResponses.put(tunnel, responseContent);
                	}
                	else if (response.getPerformative() == ACLMessage.REFUSE) {
                		someRequestRejected = true;
                		requestResponses.put(tunnel, responseContent);
                	}

                }
                else {
                	block();
                }
                
            }
            
            System.out.println(CYAN+getLocalName()+": TIMEOUT PASSED, received "+requestResponses.size()+"/"+sentRequests.size()+" requests"+RESET);
            
            analyseTrainResponses(requestResponses, someRequestRejected);
            
        }
        
        // take adequate actions with respect to the number of responses received and if some of them were rejected
        private void analyseTrainResponses(Map<String, String> requestResponses, boolean someRequestRejected) {
        	
        	// after a time if some nodes did not respond, treat them as not accepted
            if (requestResponses.size() < sentRequests.size()) {
            	// look for another path because not all nodes responded
            	sendCANCELMessageToAllTunnels();
            	if(!forcedReservation) {
            		currentStep = 3; // try with a new path
        		}
        		else {
        			// we force reservation with a least delay
        			chooseLeastDelayedPath();
            		currentStep = 0;
        		}
            }
            else {

                if (!someRequestRejected) {
                	// all paths were accepted, send confirmations to tunnels
                	System.out.println(CYAN+getLocalName()+": All paths accepted! Sending confirmations..."+RESET);
                	currentStep = 2;
                    
                } else {
                	// some reservations were not accepted, we have all responses
                	sendCANCELMessageToAllTunnels(); // for sure we change the path
                	
                	// try to reserve nodes but with acceptable delay
                	System.out.println(CYAN+getLocalName()+": Some reservations were not accepted, is the delay acceptable?"+RESET);
                	resolveNewDelayedPath(requestResponses);
                	
                }
            }
        }
        
        // calculate the delay of the path, regarding the times that rejected node provided
        // if delay is acceptable set delayed path as a current one, otherwise choose the least delayed one or generate new path
        private void resolveNewDelayedPath(Map<String, String> requestResponses) {
        	Map<String, SimpleEntry<Integer, Integer>> newDelayedPath = new LinkedHashMap<>();
        	
        	// we calculate train total delay
        	int totalDelay = 0;
        	
        	Map.Entry<String, SimpleEntry<Integer, Integer>> firstNode = pathSegmentsPlanned.get(0);
        	//int prevNodeDeparture = firstNode.getValue().getValue();
        	int prevNodeArrival = firstNode.getValue().getKey();
        	
        	String previousNode = startNode;
        	int prevNodeDeparture = firstNode.getValue().getValue();
        	int prevPlannedNodeDeparture=prevNodeDeparture;
        	// iterate over each request, calculate the delay, update arrival/departure times
        	for (String node : sentRequests) { // preserve the order of the nodes
        	    String responseContent = requestResponses.get(node);
        	    
    	        for (int i = 0; i < pathSegments.size(); i++) {
    	            Map.Entry<String, SimpleEntry<Integer, Integer>> segment = pathSegments.get(i);
    	            
    	            if (segment.getKey().equals(node)) {
    	            	int plannedNodeArrival = segment.getValue().getKey();
    	                int plannedNodeDeparture = segment.getValue().getValue();
        	            int passingNodeTime = plannedNodeDeparture - plannedNodeArrival;
        	            int passingRailTime = plannedNodeArrival - prevPlannedNodeDeparture;
        	            
    	                int newArrival = 0;
    	                
    	                if(responseContent!=null) {
    	                	if (responseContent.contains("NOT AVAILABLE") || responseContent.contains("OCCUPIED")) {
    	                		newArrival = Integer.parseInt(responseContent.split(":")[1]);
    	                		
                    	        // calculate delay based on different nodes arrivals, update departure time from previous node
    	                		if((prevNodeDeparture + passingRailTime) < newArrival) {
    	                			int diffArivals = newArrival - plannedNodeArrival;
            	                	prevNodeDeparture = newArrival - passingRailTime;
                        	        totalDelay = diffArivals;
    	                		}
    	                	}
    	                }
    	                
    	                // add updated times to new path
    	                SimpleEntry<Integer, Integer> updatedTimes = new SimpleEntry<>(prevNodeArrival, prevNodeDeparture);
    	                newDelayedPath.put(previousNode, updatedTimes);
    	                
    	                previousNode = node;
    	                
    	                prevNodeArrival = plannedNodeArrival + totalDelay;
    	                prevNodeDeparture = prevNodeArrival + passingNodeTime;
    	                prevPlannedNodeDeparture = plannedNodeDeparture;

        	            break;
    	            }
    	        }
    	        
        	}
        	
        	// adding last node because we base on previous values
        	SimpleEntry<Integer, Integer> updatedTimes = new SimpleEntry<>(prevNodeArrival, prevNodeDeparture);
            newDelayedPath.put(previousNode,updatedTimes);
        	delayedPaths.put(totalDelay, newDelayedPath);
        	
        	if (totalDelay > maxDelay) {
        		System.out.println(CYAN+getLocalName()+": Delay is NOT ACCEPTABLE! Added "+newDelayedPath+" to delayed paths..."+RESET);
        		
        		if(!forcedReservation) {
        			currentStep = 3;
        		}
        		else {
        			chooseLeastDelayedPath();
            		currentStep = 0;
        		}
    	    }
        	else { 
        		// delay is acceptable - come back to step 1, re-sent all requests with a new delayed path
    			System.out.println(CYAN+getLocalName()+": Delay is ACCEPTABLE! New slightly delayed path: "+newDelayedPath+RESET);
        		currentPath = newDelayedPath;
        		currentStep = 0;
        	}
        }
        
        // from the map of all previously calculated delayed paths choose the least delayed one and set it as a current one
        private void chooseLeastDelayedPath() {
        	if(delayedPaths.size() > 0) {
				int leastDelay = Collections.min(delayedPaths.keySet());
    			Map<String, SimpleEntry<Integer, Integer>> leastDelayedPath = delayedPaths.get(leastDelay);
    			currentPath = leastDelayedPath;
    			delayedPaths.remove(leastDelay);
    			System.out.println(CYAN+getLocalName()+": Choose least delayed path as current one: "+leastDelayedPath+RESET);
			}
        }
        
        // send confirmations to all of the nodes in the current path
        private void sendConfirmations() {
        	for (int i = 0; i < pathSegments.size(); i++) {
            	Map.Entry<String, SimpleEntry<Integer, Integer>> segment = pathSegments.get(i);
            	
            	String tunnel = segment.getKey();
                ACLMessage confirm = new ACLMessage(ACLMessage.REQUEST);
                confirm.addReceiver(getAID(tunnel));
                confirm.setContent("CONFIRM RESERVATION");
                send(confirm);
            }
        }
        
        // send request for a new path to TransitSystemAgent
        private void requestNewPath() {
        	System.out.println(CYAN+getLocalName() + ": Requesting new path from TransitSystemAgent..."+RESET);
    		pathSegments = new ArrayList<>(currentPath.entrySet());
    		Map.Entry<String, SimpleEntry<Integer, Integer>> startSegment = pathSegments.get(0);
    		Map.Entry<String, SimpleEntry<Integer, Integer>> endSegment = pathSegments.get(pathSegments.size()-1);
         	
         	String endNode = endSegment.getKey();
            int startTimeNode = startSegment.getValue().getKey();

            ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
            request.addReceiver(new AID("TransitSystemAgent", AID.ISLOCALNAME));
            request.setContent("REQUEST PATH:"+startNode+":"+startTimeNode+":"+endNode+":"+trainSize);
            send(request);
        }
        
        
        // wait for the response with a new path and set it as a current one, if there are no other paths - choose least delayed one
        private void receiveNewPath() {
        	
        	ACLMessage reply = receive();
            if (reply != null) {
            	String responseContent = reply.getContent();
            	if(reply.getPerformative() == ACLMessage.INFORM) {
            		// if there is no other path try to confirm it no matter of delay
            		if(responseContent.contains("NO PATHS")) {
            			chooseLeastDelayedPath();
                    	forcedReservation = true; // turn forced reservation, as we do not have more paths
                    }
                    else if (responseContent.contains("NEW PATH")){
                    	// update current path
                    	String newPathRaw = ((reply.getContent()).split(":\\["))[1];
                    	newPathRaw = newPathRaw.replaceAll("\\[", "").replaceAll("\\]", "");
                    	currentPath = transformRawPath(newPathRaw);
                    	System.out.println(CYAN+getLocalName()+": Change of currentPath to "+currentPath+RESET);
                    }
            		currentStep = 0;
            	}
            }
            else {
            	block();
            }
        }
        
        // informing Transit System that train has planned its path, sending DONE message
        private void informTransitSystem() {
        	System.out.println(CYAN+getLocalName()+": PATH RESERVED - Sending DONE to TransitSystemAgent!"+RESET);
    		ACLMessage doneMessage = new ACLMessage(ACLMessage.INFORM);
    		doneMessage.addReceiver(new AID("TransitSystemAgent", AID.ISLOCALNAME));
    		doneMessage.setContent("DONE:"+currentPath);
            send(doneMessage);
        }
        
        // inform tunnels that they should not wait any longer for the confirmation
        private void sendCANCELMessageToAllTunnels() {
        	for (int i = 0; i < pathSegments.size(); i++) {
            	Map.Entry<String, SimpleEntry<Integer, Integer>> segment = pathSegments.get(i);
        		String tunnel = segment.getKey();
            	System.out.println(RED+getLocalName() + " cancelled "+tunnel+" reservation"+RESET);
            	ACLMessage inform = new ACLMessage(ACLMessage.CANCEL);
                inform.addReceiver(getAID(tunnel));
                // send the value of the previously declared arrival time
                inform.setContent("CANCEL:"+segment.getValue().getKey());
                send(inform);
            }
        	// wait a bit to let the all nodes update their arrival times
        	block(4000);
        }
        
        @Override
        public boolean done() {
            return currentPathConfirmed;
        }

    }
    protected void takeDown() {
    System.out.println(RED+"TrainAgent "+getLocalName()+" terminating"+RESET);
    }

}


