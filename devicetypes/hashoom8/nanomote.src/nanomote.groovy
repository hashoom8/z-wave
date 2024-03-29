/**
 *  Aeotec NanoMote One/Quad v1.0
 *  (Models: ZWA003-A/ZWA004-A)
 *
 *  Hank Scene Controller/Hank Four-Key Scene Controller
 *  (Models: HKZW-SCN01/HKZW-SCN04)
 *
 *  Author: 
 *    Kevin LaFramboise (krlaframboise)
 *
 *  Changelog:
 *
 *    1.0 (05/26/2018)
 *      - Initial Release
 *
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
 */
metadata {
	definition (
		name: "nanomote", 
		namespace: "hashoom8", 
		author: "Kevin LaFramboise"
	) {
		capability "Sensor"
		capability "Battery"
		capability "Button"
		capability "Configuration"
		capability "Refresh"
		
		attribute "lastCheckIn", "string"
		attribute "lastAction", "string"
		
		fingerprint mfr:"0371", prod:"0102", model:"0003", deviceJoinName: "Aeotec NanoMote Quad"// ZWA003-A
		
		fingerprint mfr: "0208", prod:"0201", model: "000B", deviceJoinName: "Hank Four-Key Scene Controller" // HKZW-SCN04		
		
		fingerprint mfr:"0371", prod:"0102", model:"0004", deviceJoinName: "Aeotec NanoMote One" // ZWA004-A
					
		fingerprint mfr: "0208", prod:"0201", model: "0009", deviceJoinName: "Hank Scene Controller" // HKZW-SCN01
	}
	
	simulator { }
	
	preferences {
		input "debugOutput", "bool", 
			title: "Enable debug logging?", 
			defaultValue: true, 
			required: false
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"lastAction", type: "generic", width: 6, height: 4, canChangeIcon: false){
			tileAttribute ("device.lastAction", key: "PRIMARY_CONTROL") {
				attributeState "lastAction", 
					label:'${currentValue}', 
					icon:"st.unknown.zwave.remote-controller", 
					backgroundColor:"#cccccc"				
			}	
			tileAttribute ("device.battery", key: "SECONDARY_CONTROL") {
				attributeState "battery", label:'Battery ${currentValue}%'
			}
		}
		
		standardTile("refresh", "device.refresh", width: 2, height: 2) {
			state "refresh", label:'Refresh', action: "refresh", icon:"st.secondary.refresh-icon"
		}
		
		main "lastAction"
		details(["lastAction", "refresh"])
	}
}


def updated() {	
	// This method always gets called twice when preferences are saved.
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {		
		state.lastUpdated = new Date().time
		logTrace "updated()"

		logForceWakeupMessage "The configuration will be updated the next time the device wakes up."		
	}		
}

private isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time) 
}


def configure() {
	logDebug "configure()"
	
	def cmds = []	
	if (!device.currentValue("battery") || state.pendingRefresh) {
		cmds << batteryGetCmd()
	}
	
	if (!device.currentValue("numberOfButtons") || state.pendingRefresh) {
		cmds << manufacturerSpecificGetCmd()
	}
	state.pendingRefresh = false
	return cmds ? delayBetween(cmds, 1000) : []
}

def refresh() {	
	logForceWakeupMessage "The sensor data will be refreshed the next time the device wakes up."
	state.pendingRefresh = true
}

private logForceWakeupMessage(msg) {
	logDebug("${msg}  You can force the device to wake up immediately by holding the button until the LED turns green.")
}


def parse(String description) {
	def result = []
	try {
		def cmd = zwave.parse(description, commandClassVersions)
		if (cmd) {
			result += zwaveEvent(cmd)
		}
		else {
			logDebug "Unable to parse description: $description"
		}
	}
	catch (e) {
		log.error "$e"
	}
	
	sendEvent(name: "lastCheckIn", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
	
	return result
}


def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapCmd = cmd.encapsulatedCommand(commandClassVersions)
		
	def result = []
	if (encapCmd) {
		result += zwaveEvent(encapCmd)
	}
	else {
		log.warn "Unable to extract encapsulated cmd from $cmd"
	}
	return result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd) {
	logTrace "WakeUpNotification: $cmd"
	
	def cmds = []	
	cmds += configure()
			
	if (cmds) {
		cmds << "delay 2000"
	}
	cmds << wakeUpNoMoreInfoCmd()	
	return response(cmds)
}


def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
	logTrace "BatteryReport: $cmd"
	
	def val = (cmd.batteryLevel == 0xFF ? 1 : cmd.batteryLevel)
		
	if (val > 100) { val = 100 }
		
	sendEvent(getEventMap("battery", val, null, null, "%"))
	return []
}	


def zwaveEvent(physicalgraph.zwave.commands.centralscenev1.CentralSceneNotification cmd) {
	logTrace "CentralSceneNotification: ${cmd}"
	
	def btn = cmd.sceneNumber
	def action	
	switch (cmd.keyAttributes) {
		case 0:
			action = "pushed"
			break
		case 1:			
			// Ignore button released event.
			break
		case 2:
			action = "held"
			break
	}
	
	if (action) {
		sendButtonEvent(btn, action)
	}
	return []
}

private sendButtonEvent(btn, action) {
	logDebug "Button ${btn} ${action}"
	
	def lastAction = (device.currentValue("numberOfButtons") == 1) ? "${action}" : "${action} ${btn}"

	sendEvent(name:"lastAction", value: "${lastAction}".toUpperCase(), displayed: false)
	
	sendEvent(
		name: "button", 
		value: "${action}", 
		descriptionText: "Button ${btn} ${action}",
		data: [buttonNumber: btn],
		displayed: true,
		isStateChange: true)
}


def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	logTrace "ManufacturerSpecificReport $cmd"
	
	def btnCount = 4
	if ((cmd.productTypeId == 258 && cmd.productId == 4) || (cmd.productTypeId == 513 && cmd.productId == 9)) {	
		// Aeotec NanoMote One or Hank Scene Controller
		btnCount = 1
	}
	sendEvent(name: "numberOfButtons", value: btnCount)
	return []
}


def zwaveEvent(physicalgraph.zwave.Command cmd) {
	logDebug "Unhandled Command: $cmd"
	return []
}


private getEventMap(name, value, displayed=null, desc=null, unit=null) {	
	def isStateChange = (device.currentValue(name) != value)
	displayed = (displayed == null ? isStateChange : displayed)
	def eventMap = [
		name: name,
		value: value,
		displayed: displayed,
		isStateChange: isStateChange
	]
	if (desc) {
		eventMap.descriptionText = desc
	}
	if (unit) {
		eventMap.unit = unit
	}	
	logTrace "Creating Event: ${eventMap}"
	return eventMap
}


private wakeUpNoMoreInfoCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpNoMoreInformation())
}

private batteryGetCmd() {
	return secureCmd(zwave.batteryV1.batteryGet())
}

private manufacturerSpecificGetCmd() {
	return secureCmd(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
}

private secureCmd(cmd) {
	if (zwaveInfo?.zw?.contains("s") || ("0x98" in device.rawDescription?.split(" "))) {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	}
	else {
		return cmd.format()
	}	
}

private getCommandClassVersions() {
	[
		0x26: 2, // Switch Multilevel
		0x55: 1, // TransportServices (2)
		0x59: 1, // AssociationGrpInfo
		0x5A: 1, // DeviceResetLocally
		0x5B: 1, // Central Scene (3)
		0x5E: 2, // ZwaveplusInfo
		0x70: 1, // Configuration
		0x72: 2, // ManufacturerSpecific
		0x73: 1, // Powerlevel
		0x7A: 2, // FirmwareUpdateMd (3)
		0x80: 1, // Battery
		0x84: 2, // WakeUp
		0x85: 2, // Association
		0x86: 1, // Version (2)
		0x98: 1, // Security
		0xEF: 1	 // Mark		
		// Security S2
		// Supervision
	]
}


private convertToLocalTimeString(dt) {
	try {
		def timeZoneId = location?.timeZone?.ID
		if (timeZoneId) {
			return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
		}
		else {
			return "$dt"
		}	
	}
	catch (e) {
		return "$dt"
	}
}


private getLastEventTime(name) {
	return device.currentState("${name}")?.date?.time
}

private minutesElapsed(time, minutes) {
	if (time) {
		def cutoff = (new Date().time - (minutes * 60 * 1000)) + 5000
		return (time > cutoff)
	}
	else {
		return false
	}
}


private logDebug(msg) {
	if (settings?.debugOutput != false) {
		log.debug "$msg"
	}
}

private logTrace(msg) {
	// log.trace "$msg"
}