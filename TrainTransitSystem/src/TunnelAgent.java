import jade.core.Agent;

import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;

public class TunnelAgent extends Agent {
	
	// properties given as arguments
    private List<SimpleEntry<Integer, Integer>> nodeAvailability = new ArrayList<>();
    // neighbor nodes and passing time to it
    private Map<String, Integer> neighborsA= new HashMap<>(); // rails starting from A
    private Map<String, Integer> neighborsB= new HashMap<>(); // rails starting from B
    
    // maps to track current reservations of the node and rails leading to it
    private Map<String, List<SimpleEntry<Integer, Integer>>> neighborRailAvailability = new HashMap<>();
    
    // tracking of all requests of trains, node can analyze only one of them at the time
    private final Map<String, ACLMessage> pendingRequests = new LinkedHashMap<>();
    
 // colors to color the output
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
        	
        	// process information about neighbors nodes and rails leading to them
        	String neighborRawInfo = args[0].toString();
        	neighborRawInfo = neighborRawInfo.replaceAll("\\[", "").replaceAll("\\]", "");
        	
            String[] neighborsList = neighborRawInfo.split("\\|");
            for (String neighbor : neighborsList) {
            	System.out.println(neighbor);
                String[] connectionSplit = neighbor.split(":");
                if(connectionSplit[0].equals("A")) {
                	neighborsA.put(connectionSplit[1]+"Agent",Integer.parseInt(connectionSplit[2]));
                }
                else {
                	neighborsB.put(connectionSplit[1]+"Agent",Integer.parseInt(connectionSplit[2]));
                }
            }
            
            // if we have additional info about node being OCCUPIED from the beginning (if it is out of order)
            if(args.length > 1) {
            	String state = args[1].toString();
            	// if it is occupied, it is provided with first time instant when it will be free
                if(state.contains("FAULTY")){
                	// time not goes back, we mark that node is faulty from time 0
                	int occupiedTo = Integer.parseInt(state.split(":")[1]);
                	nodeAvailability.add(new SimpleEntry<>(0, occupiedTo));
                }
            }
            
            addBehaviour(new HandleIncomingMessages());
        } 
        else {
        	// no arguments terminate agent
            doDelete();
        }
    }
    
    // handling constantly receiving requests from trains, or informations from other tunnels
    private class HandleIncomingMessages extends CyclicBehaviour {
    	// state of the node - it waits for train confirmation for specific amount of time
    	private boolean waitingForConfirmation = false;
    	
    	// currently confirming train and its request
    	private String confirmingTrain = null;
    	private ACLMessage currentRequest = null;
    	
    	private boolean firstPlatformReservation = false;
    	private int arrivalAtNode = 0;
    	
        @Override
        public void action() {
            ACLMessage msg = myAgent.receive();
            if (msg != null) {

                String senderName = msg.getSender().getLocalName();
                String content = msg.getContent();
                String[] contentSplit = content.split(":");
                String command = contentSplit[0];
                
                switch (command) {
                    case "RESERVE":
                    	if(msg.getPerformative() == ACLMessage.REQUEST) {
                    		System.out.println(getLocalName()+": Received reservation request from "+senderName);
                        	handleReservationRequest(msg);
                    	}
                        break;
                    case "INFORM ADD":
                    	if(msg.getPerformative() == ACLMessage.REQUEST) {
                    		markReservationsAddInform(senderName, contentSplit);
                    	}
                        break;
                        
                    case "INFORM REMOVE":
                    	if(msg.getPerformative() == ACLMessage.REQUEST) {
                    		markReservationsRemoveInform(senderName, contentSplit);
                    	}
                        break;
                        
                    case "CONFIRM RESERVATION":
                    	if (msg.getPerformative()==ACLMessage.REQUEST) {
                    		// ensure if it the answer from current confirming train
                        	if(confirmingTrain != null && confirmingTrain.equals(senderName)) {
                        		confirmTrainReservation(senderName);
                        	}
                    	}
                        break;
                        
                    case "CANCEL":
                    	
                    	// cancel reservation from of a current confirming train, that means we already sent the OK message
                        if(currentRequest!=null && senderName.equals(confirmingTrain)) {
                    		String requestContent = currentRequest.getContent();
                    		String[] requestSplit= requestContent.split(":");
                            String fromStation = requestSplit[1];
                            String fromNode = requestSplit[2];
                            int arrivalTime = Integer.parseInt(requestSplit[3]);
                            
                            if(firstPlatformReservation) {
                            	nodeAvailability.removeIf(entry -> entry.getKey() == arrivalAtNode);
                            	arrivalAtNode = 0;
                            	firstPlatformReservation = false;
                            }
                            else {
                            	// notify other neighbors nodes about the reservation
                                Map<String, Integer> neighbors = new HashMap<>(fromStation.contains("PlatformA") ? neighborsA : neighborsB);
                            	int railTravelTime = neighbors.getOrDefault(fromNode,0);
                            	
                                int currentArrivalRail = arrivalTime - railTravelTime;
                            	removeReservation(fromNode, currentArrivalRail, arrivalTime);
                            }
                            resolveNextRequest();
                    	}
                        else {
                        	// or remove pending request from sender
                        	pendingRequests.remove(senderName);
                        }
                        
                        
                    	break;
                }
            } else {
                block();
            }
        }
        
        // notify about confirmation of the current request, resolve next request
        private void confirmTrainReservation(String senderName) {
        	String requestContent = currentRequest.getContent();
    		String[] requestSplit= requestContent.split(":");
    		String fromStation = requestSplit[1];
    		String fromNode = requestSplit[2];
    		
            if (fromStation.equals(getLocalName())) {
            	System.out.println(getLocalName()+": "+GREEN+"Received CONFIRMATION from "+senderName+", it will start it's path here"+RESET);
            }
            else {
            	System.out.println(getLocalName()+": "+GREEN+"Received CONFIRMATION from "+senderName+", it will occupy rail from "+ fromNode+RESET);
            }
        	
            
            resolveNextRequest();
        }
        
        // marking reservation in tables and sending the INFORMs to other node
        private void addReservation(String fromNode, int currentArrivalRail, int arrivalTime, int departureTime) {
        	
        	List<SimpleEntry<Integer, Integer>> railAvailabilityList = neighborRailAvailability
                    .getOrDefault(fromNode, new ArrayList<>());
            
            // mark reservation in rails table
            railAvailabilityList.add(new SimpleEntry<>(currentArrivalRail, arrivalTime));
            neighborRailAvailability.put(fromNode, railAvailabilityList);
            
            // mark reservation in train freeAt table
            nodeAvailability.add(new SimpleEntry<>(arrivalTime, departureTime));
            
            ACLMessage inform = new ACLMessage(ACLMessage.REQUEST);
            inform.addReceiver(getAID(fromNode));
            inform.setContent("INFORM ADD:" + currentArrivalRail + ":" + arrivalTime);
            send(inform);
            block(2000);
        }
        
        // removing reservations from table and sending inform to other node
        private void removeReservation(String fromNode, int currentArrivalRail, int arrivalTime) {
        	
            List<SimpleEntry<Integer, Integer>> railAvailabilityList = neighborRailAvailability.getOrDefault(fromNode, new ArrayList<>());
            railAvailabilityList.removeIf(entry -> entry.getKey() == currentArrivalRail && entry.getValue() == arrivalTime);
            neighborRailAvailability.put(fromNode, railAvailabilityList);
            
            nodeAvailability.removeIf(entry -> entry.getKey() == arrivalTime);

            ACLMessage inform = new ACLMessage(ACLMessage.REQUEST);
            inform.addReceiver(getAID(fromNode));
            inform.setContent("INFORM REMOVE:" + currentArrivalRail + ":" + arrivalTime);
            send(inform);
            block(2000);
        }
        
        // mark incoming, new reservations in rail availability table
        private void markReservationsAddInform(String senderName, String[] contentSplit) {
    		int railReservedFrom = Integer.parseInt(contentSplit[1]);
            int railReservedTo = Integer.parseInt(contentSplit[2]);

            List<SimpleEntry<Integer, Integer>> railAvailabilityList = neighborRailAvailability
                    .getOrDefault(senderName, new ArrayList<>());
            railAvailabilityList.add(new SimpleEntry<>(railReservedFrom, railReservedTo));
            neighborRailAvailability.put(senderName, railAvailabilityList);
        }
        
        // mark incoming, removals of reservations in rail availability table
        private void markReservationsRemoveInform(String senderName, String[] contentSplit) {
    		int currentArrivalRail = Integer.parseInt(contentSplit[1]);
            int arrivalTime = Integer.parseInt(contentSplit[2]);
            
            List<SimpleEntry<Integer, Integer>> railAvailabilityList = neighborRailAvailability.getOrDefault(senderName, new ArrayList<>());
            railAvailabilityList.removeIf(entry -> entry.getKey() == currentArrivalRail && entry.getValue() == arrivalTime);
            neighborRailAvailability.put(senderName, railAvailabilityList);
            nodeAvailability.removeIf(entry -> entry.getKey() == arrivalTime);
        }
        
        // function to check validity of request, reply to requesting tunnel OK or NOT AVAILABLE (+when it will be free) message
        // if currently we are waiting for some confirmation add request as a pending one
        private void handleReservationRequest(ACLMessage msg) {
        	String trainName = msg.getSender().getLocalName();
            String content = msg.getContent();
            String[] contentSplit = content.split(":");
        	String fromStation = contentSplit[1];
            String fromNode = contentSplit[2];
            int arrivalTime = Integer.parseInt(contentSplit[3]);
            int departureTime = Integer.parseInt(contentSplit[4]);
            
            
            ACLMessage reply = msg.createReply();
            
        	if (!isAvailable(arrivalTime, departureTime)) {
                int whenFree = calculateWhenAvailable(arrivalTime,departureTime);
                System.out.println(getLocalName()+": Reply "+RED+"NOT AVAILABLE"+RESET+" to "+trainName+", node will be free at "+whenFree);
                replyNotAvailable(reply, whenFree);
            } 
        	else {
    			// the node is free at given arrival/departure time
        		
        		// no need to check and inform neighbor nodes, rails as it is the start station of the train
        		if(fromStation.equals(getLocalName()) && !waitingForConfirmation) {
        			System.out.println(getLocalName()+": Has free rail leading to it, "+GREEN+"OK"+RESET+" to " + trainName);
                    reply.setPerformative(ACLMessage.AGREE);
                    reply.setContent("OK");
                    send(reply);
                    
                    // mark train as the one from which we wait for response
                    waitingForConfirmation = true;
                    confirmingTrain = trainName;
                    currentRequest = msg;
                    
                    firstPlatformReservation = true;
                    arrivalAtNode = arrivalTime;
                    
                    nodeAvailability.add(new SimpleEntry<>(arrivalTime, departureTime));

        		}
        		else {
        			if(fromStation.equals(getLocalName())){
        				// just put it as a pending request 
        				pendingRequests.put(trainName, msg);
        			}
        			else {
        				// in this case we need to check information about neighbor rails and nodes
                    	int arrivalRail = calculateArrivalRailTime(fromStation, fromNode, arrivalTime);
                    	
                    	// if the rail leading to node is available
                        if (arrivalRail > 0) {

                    		if (waitingForConfirmation) {
                        		//just adding request to pending request list
                                pendingRequests.put(trainName, msg);
                        	}
                        	else {
                        		replyOKMessage(reply, trainName, msg);
                        	}
                        	
                        } else {
                            // there is no free rail available at given arrival/departure time
                        	List<SimpleEntry<Integer, Integer>> railAvailabilityList = neighborRailAvailability
                                    .getOrDefault(fromNode, new ArrayList<>());
                        	
                        	int nextAvailableArrivalRail = findNextAvailableTimeRail(railAvailabilityList, arrivalRail, arrivalTime);
                        	Map<String, Integer> neighbors = new HashMap<>(fromStation.contains("PlatformA") ? neighborsA : neighborsB);
                        	int railTravelTime = neighbors.getOrDefault(fromNode,0);
                            
                            int arrivalAtNode = railTravelTime+nextAvailableArrivalRail;
                            System.out.println(getLocalName()+": NO FREE rail leading from "+fromNode+" to "+getLocalName()
                        			+ ", reply "+RED+"NOT AVAILABLE"+RESET+" to "+trainName+", node will be free at "+arrivalAtNode);
                            replyNotAvailable(reply, arrivalAtNode);
                        }
        			}	
        		}
            }
        }
        
        private void replyNotAvailable(ACLMessage reply, int nextFreeTimeSlot) {
        	reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent("NOT AVAILABLE:" + nextFreeTimeSlot);
            send(reply);
        }
        
        // processing next request from a pending requests, checking its validity by calling again handleReservationRequest function on it
        private void resolveNextRequest() {
        	waitingForConfirmation = false;
        	confirmingTrain = null;
    		currentRequest = null;
    		firstPlatformReservation = false;
    		
        	if(!pendingRequests.isEmpty()) {
                Map.Entry<String, ACLMessage> nextRequest = pendingRequests.entrySet().iterator().next();
                ACLMessage request = nextRequest.getValue();
                pendingRequests.remove(request.getSender().getLocalName(), request);
                handleReservationRequest(request);                
    		}
        }
        
        private void replyOKMessage(ACLMessage reply, String trainName, ACLMessage msg) {
        	
        	System.out.println(getLocalName()+": Has free rail leading to it, "+GREEN+"OK"+RESET+" to " + trainName);
            reply.setPerformative(ACLMessage.AGREE);
            reply.setContent("OK");
            send(reply);
            
            // mark train as the one from which we wait for response
            waitingForConfirmation = true;
            confirmingTrain = trainName;
            currentRequest = msg;
            firstPlatformReservation = false;
            
            String requestContent = currentRequest.getContent();
    		String[] requestSplit= requestContent.split(":");
            String fromStation = requestSplit[1];
            String fromNode = requestSplit[2];
            int arrivalTime = Integer.parseInt(requestSplit[3]);
            int departureTime = Integer.parseInt(requestSplit[4]);
            
            // notify other nodes about the reservation
            int currentArrivalRail = calculateArrivalRailTime(fromStation, fromNode, arrivalTime);
            
            addReservation(fromNode, currentArrivalRail, arrivalTime, departureTime);
            
        }
        
        // look for a rail that leads from node to myAgent, calculate when the train should arrive at the connecting rail
        // if arrivalRail = 0 -> that means rail is occupied in given time slot
        private int calculateArrivalRailTime(String fromStation, String fromNode, int arrivalTime) {
        	
        	Map<String, Integer> neighbors = new HashMap<>(fromStation.contains("PlatformA") ? neighborsA : neighborsB);
        	
        	List<SimpleEntry<Integer, Integer>> railAvailabilityList = neighborRailAvailability
                    .getOrDefault(fromNode, new ArrayList<>());
        	
        	// we assume that we have properly configurated graph given paths
        	int railTravelTime = neighbors.getOrDefault(fromNode,0);
            int arrivalRail = (arrivalTime - railTravelTime);
            
            
        	for (SimpleEntry<Integer, Integer> timeSlot : railAvailabilityList) {
                int existingArrival = timeSlot.getKey();
                int existingDeparture = timeSlot.getValue();

                // check for overlapping time slots, arrivalTime is actually 'departure at rail' time
                if (arrivalRail < existingDeparture && arrivalTime > existingArrival) {
                	return 0;
                }

            }
            return arrivalRail;
        }
        
        // calculate first time instant when node will be free
        private int calculateWhenAvailable(int arrivalTime, int departureTime) {
            int nextFreeTime = arrivalTime;

            for (SimpleEntry<Integer, Integer> interval : nodeAvailability) {
                int occupiedStart = interval.getKey();
                int occupiedEnd = interval.getValue();

                if (occupiedEnd <= nextFreeTime) {
                    continue;
                }

                if (nextFreeTime < occupiedEnd && departureTime > occupiedStart) {
                    nextFreeTime = occupiedEnd;
                }
            }

            return nextFreeTime;
        }
        
        // checking if given arrival and departure time is overlapping with one of already planned occupations
        private boolean isAvailable(int arrivalTime, int departureTime) {
            for (SimpleEntry<Integer, Integer> interval : nodeAvailability) {
                int occupiedFrom = interval.getKey();
                int occupiedTo = interval.getValue();
                if (!(departureTime <= occupiedFrom || arrivalTime >= occupiedTo)) {
                	return false;
                }
                
            }
            return true;
        }
        
        // find the first free time instant that given rail will be available, time should be later than planned train arrival at rail
        private int findNextAvailableTimeRail(List<SimpleEntry<Integer, Integer>> railAvailabilityList, int arrivalRail, int departureRail) {
            railAvailabilityList.sort(Comparator.comparingInt(SimpleEntry::getKey));
        	
            int laterArrival = arrivalRail;
            for (SimpleEntry<Integer, Integer> timeSlot : railAvailabilityList) {
                int occupiedFrom = timeSlot.getKey();
                int occupiedTo = timeSlot.getValue();
                 
                // skip reservations that occur before arrivalRail
                if (occupiedTo < laterArrival) {
                    continue;
                }

                // check if the rail is free for the entire rail travel time to the node
                if ((laterArrival + (departureRail - arrivalRail)) <= occupiedFrom) {
                    return laterArrival;
                }
                laterArrival = occupiedTo;
            }

            return laterArrival;
        }
        
    }

    @Override
    protected void takeDown() {
        System.out.println("TunnelAgent " + getLocalName() + " terminating");
    }
}
