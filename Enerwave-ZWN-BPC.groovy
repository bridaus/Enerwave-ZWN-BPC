/**
 *  Enerwave Ceiling Mounted Motion Sensor
 *
 *  Copyright 2015 Brian Gudauskas
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Updates
 *	12/12 BG
 		Removed extra commands in configure, simplified devicetype
 *  11/29 BG
 *		Set wake back to six hours
 * 		Added delay in configure
 * 		Added configure to install and update routines
 * 		Simplified test mode/debug
 * 		Automated the wake up, putting into test mode shortens wake up 
 */
 
metadata 
{
    definition (namespace: "bridaus", name: "Enerwave Ceiling Mounted Motion Sensor", author: "Brian Gudauskas") 
    {
        capability "Motion Sensor"
		capability "Battery"
        capability "Configuration"
        
        fingerprint deviceId:"0x2001", inClusters:"0x30, 0x84, 0x80, 0x85, 0x72, 0x86, 0x70"        
	}

    tiles 
    {
		standardTile("motion", "device.motion", width: 2, height: 2) 
        {
			state("active", label:'motion', icon:"st.motion.motion.active", backgroundColor:"#53a7c0")
			state("inactive", label:'no motion', icon:"st.motion.motion.inactive", backgroundColor:"#ffffff")
		}
        
        valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat") 
        {
            state("battery", label:'${currentValue}% battery', unit:"")
        }

        standardTile("configure", "device.switch", inactiveLabel: false, decoration: "flat") {
            state "default", label:"", action:"configure", icon:"st.secondary.configure"
        }

        main "motion"
        details(["motion", "battery", "configure"])
    }
}

def installed()
{
	log.debug "Installed with settings: ${settings}"
}

def updated()
{
	configure()
	log.debug "Updated with settings: ${settings}"
}

def configure() {

	def commands = [
        zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId:zwaveHubNodeId).format(), 	// Set device association for motion commands
	]
    commands << 
	log.debug("Configure $device.label sending configuration: $commands")
	return delayBetween(commands, 200)
}

def parse(String description) {
		if (description == "updated") return;  //catches a weird command on installation

		def result = null
        def cmd = zwave.parse(description)
        if (cmd) {
                result = zwaveEvent(cmd)
                log.debug "Parsed ${cmd} to ${result.inspect()}"
        } else {
                log.debug "Non-parsed event: ${description}"
        }
        result
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
        def map = [ name: "battery", unit: "%" ]
        if (cmd.batteryLevel == 0xFF) {  // Special value for low battery alert
                map.value = 1
                map.descriptionText = "${device.displayName} has a low battery"
                map.isStateChange = true
        } else {
                map.value = cmd.batteryLevel
        }
        // Store time of last battery update so we don't ask every wakeup, see WakeUpNotification handler
        state.lastbatt = new Date().time
        createEvent(map)
}

// Battery powered devices can be configured to periodically wake up and
// check in. They send this command and stay awake long enough to receive
// commands, or until they get a WakeUpNoMoreInformation command that
// instructs them that there are no more commands to receive and they can
// stop listening.
def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd)
{
		if (settings.testMode) log.debug "WakeUp Command: $cmd" //ADD
        
		def result = [createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)]

        // Only ask for battery if we haven't had a BatteryReport in a while
        if (!state.lastbatt || (new Date().time) - state.lastbatt > 24*60*60*1000) {
                result << response(zwave.batteryV1.batteryGet())
                result << response("delay 1200")  // leave time for device to respond to batteryGet
        }
        result << response(zwave.wakeUpV2.wakeUpNoMoreInformation())
        result
}

// Many sensors send BasicSet commands to associated devices.
// This is so you can associate them with a switch-type device
// and they can directly turn it on/off when the sensor is triggered.
def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd)
{
	if (settings.testMode) log.debug "BasicV1 Command: $cmd" //ADD

	createEvent(name:"sensor", value: cmd.value ? "active" : "inactive")

	def result = []
    
    if (cmd.value == 255) 
    {
    	result << createEvent([name: "motion", value: "active", descriptionText: "Detected Motion"])
    }
    else
    {
    	result << createEvent([name: "motion", value: "inactive", descriptionText: "Motion Has Stopped"])
    }
    
	result

}

def zwaveEvent(physicalgraph.zwave.Command cmd) 
{
    if (settings.testMode) log.debug "Unhandled Command: $cmd"
}

//12/12/15 No evidence this is being used, leaving in case
def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd) {
        def result = []
        if (cmd.nodeId.any { it == zwaveHubNodeId }) {
                result << createEvent(descriptionText: "$device.displayName is associated in group ${cmd.groupingIdentifier}")
        } else if (cmd.groupingIdentifier == 1) {
                // We're not associated properly to group 1, set association
                result << createEvent(descriptionText: "Associating $device.displayName in group ${cmd.groupingIdentifier}")
                result << response(zwave.associationV1.associationSet(groupingIdentifier:cmd.groupingIdentifier, nodeId:zwaveHubNodeId))
        }
        result
}
