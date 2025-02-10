# MAS-TrainTransitSystem
University project for the Symbolic And Distributed Artificial Intelligence course, University of Genoa. Development and design of Multiagent System using JADE programming language.

## Introduction
The project focuses on the transit of trains through a network of single-track 
tunnels located between two stations. It uses the concept of resource allocation and 
graph traversal.
<br><br>
### Main idea
Transit in the context of this project means a train going from one station to another within a network 
of one-way tunnels. Each train has a planned, preferred path given as an argument and it aims at obtaining reservation for each tunnel included in this path.
If given timestamps (arrival/departure times) at the tunnels conflict with other trains reservations, system will force train to try other path or different timestamps.
<br><br>
Tunnels during a given timestamp allow only one train at a time to pass through it in 
one direction. They will act as shared resources that need to be distributed among the 
trains, addressing the **Multiagent Resource Allocation Problem**. The system will 
simulate real-world constraints, such as adapting train transits to delays or some 
tunnels being out of service. In addition, some of the trains have special requirements 
to pass through wider tunnels. The system will act as a MAS implementation. 
<br><br>
Agents in the system: 
- 6 Train Agents
- 10 Tunnel Agents
- 1 Transit System Agent


## Multiagent Resource Allocation Problem
This project addresses the challenge of allocating shared resources among agents by 
assigning time slots for each resource use. The solution involves implementation of 
agent behaviours for real-time message exchange, allowing for dynamic coordination 
among competing agents. <br><br>
The impact of solving such problems extends far beyond theoretical applications. In 
rail transit systems, intelligent resource allocation can help analyse the use of existing 
infrastructure: it helps identify and minimize potential bottlenecks in rail and tunnel 
networks as well as enables simulation of planned infrastructure. In addition, identified conflict points may indicate the need to rebuild the 
rail structure. Agent-based systems enable real-time adaptation to disruptions such as 
train delays or tunnel disruptions. 
<br>
<br>
The principles applied in this project are not limited to rail networks. Similar models 
can be applied to traffic management, logistics, and other domains that require 
coordination of shared resources. As industries increasingly rely on intelligent 
automation, solving these types of problems will be critical to improving efficient 
infrastructure management.

## Developed program
Program based on the concepts shown in the paper

The system developed in this project is built on the concepts presented in paper **'Dynamic 
Resource Allocation in MAS'**[1], where allocation problem is limited to negotiations 
and therefore communication between agents. However, the developed system 
presents a somewhat different approach to resource management. As soon as a train 
confirms its path defined in the tunnel network, the reservation on tunnels is locked, 
preventing other trains from overriding this reservation. In addition, if a tunnel does 
not respond within a predefined timeout period (set by the train), the train cancels its 
reservation and notifies the tunnel accordingly.  
<br><br>
This system addresses the **Multiagent Resource Allocation (MARA) problem** - '_the 
problem of distributing a number of resources amongst multiple agents_'[2]. In the 
context of this project, the term of shared "resources" is used to refer to tunnels or 
platforms and that can only be occupied by a single train agent at a given time 
interval - its arrival and departure time from a resource. 
<br><br>
<br><br>
[1] Daniela Briola, Viviana Mascardi, Maurizio Martelli, Riccardo Caccia, Carlo Milani: Dynamic Resource Allocation 
in a MAS: A Case Study from the Industry. WOA 2009: 125-1. <br>
[2] Y. Liu and Y. Mohamed, "Multi-Agent Resource Allocation (MARA) for modeling construction processes," 2008 
Winter Simulation Conference, Miami, FL, USA, 2008, pp. 2361-2369 
<br><br>
❗**Please read the 'MAS TrainTransitSystem-Report.pdf' for more details**

