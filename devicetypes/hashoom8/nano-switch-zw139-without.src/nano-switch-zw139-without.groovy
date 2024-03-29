/**
 *  Aoetec Nano Switch without power metering
 *  (Models: ZW139)
 *
 *  Author: 
 *	  Justin Dybedahl (madj42) //modified by Chris for ZW139
 *
 *    Thanks to Kevin LaFramboise (krlaframboise) for sharing the base DTH (Zooz ZEN15).
 *
 *
 *  Changelog:
 *
 *    1.0 (01/28/2019)
 *      - Initial Release
 *    1.1 (01/31/2019)
 *      - Fixed toggle for debug messaging.
 * 		- Added toggle for trace messaging to avoid log spamming.
 *		- Added toggle for disabling switch in event device is only used for power metering.
 *      - Fixed trace log message that reported old parameter value in the event of a change.
 *		- Added versioning and naming/link to DTH settings.
 *		- Added LED control parameter and more options for Instantaneous Power Values parameter.
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
		name: "nano switch zw139 without", 
		namespace: "hashoom8", 
		author: "Justin Dybedahl",
		vid:"generic-switch-power-energy"
	) {
		capability "Actuator"
		capability "Sensor"
		capability "Switch"		
		capability "Outlet"
		capability "Configuration"
		capability "Refresh"
		capability "Health Check"
		capability "Acceleration Sensor"
		
		attribute "lastCheckin", "string"
		attribute "firmwareVersion", "string"		
				
		command "reset"

		fingerprint mfr:"0086", prod:"0103", model:"008B", deviceJoinName: "Aeotec Nano Switch ZW140"
	}

	simulator { }
	
	preferences {       
			
		getBoolInput("disableSwitch", "Disable On/Off Switch", false)
		getBoolInput("traceOutput", "Enable Trace Logging", false)
        getBoolInput("debugOutput", "Enable Debug Logging", false)
        
        input title: "", description: "Aeotec Nano Switch v${clientVersion()}", displayDuringSetup: false, type: "paragraph", element: "paragraph", required: true
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"switch", type: "generic", width: 6, height: 4, canChangeIcon: true){
			tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
				attributeState "on", label: '${name}', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#00a0dc"
				attributeState "off", label: '${name}', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff"
			}
			tileAttribute ("device.acceleration", key: "SECONDARY_CONTROL") {
				attributeState "inactive", label:'INACTIVE'
				attributeState "active", label:'ACTIVE'
			}
		}
		standardTile("refresh", "device.refresh", width: 2, height: 2) {
			state "refresh", label:'Refresh', action: "refresh", icon:"st.secondary.refresh-icon"
		}
		
		valueTile("firmwareVersion", "device.firmwareVersion", decoration:"flat", width:3, height: 1) {
			state "firmwareVersion", label:'Firmware ${currentValue}'
		}
		main "switch"
		details(["switch", "refresh", "firmwareVersion"])
	}
}

def clientVersion() {
	return "1.1"
}

private getBoolInput(name, title, defaultVal) {
	input "${name}", "bool", 
		title: "${title}?", 
		defaultValue: defaultVal, 
		required: false
}

def updated() {	
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {
		state.lastUpdated = new Date().time
		
		def cmds = configure()
		return cmds ? response(cmds) : []
	}
}

def configure() {
	def result = []
	
	updateHealthCheckInterval()
		
	def cmds = []	
	
	if (!device.currentValue("firmwareVersion")) {
		cmds << versionGetCmd()
	}
	
	configParams.each { param ->	
		cmds += updateConfigVal(param)
	}
	result += delayBetweenCmds(cmds)
	
	if (!getAttrVal("energyTime")) {
		result += reset()
	}
	else {
		result += refresh()	
	}	
	return result
}

private updateConfigVal(param) {
	def cmds = []
	if (hasPendingChange(param)) {
    	def oldVal = getParamStoredIntVal(param)
		def newVal = getParamIntVal(param)
        logTrace "${param.name}(#${param.num}): changing ${getParamStoredIntVal(param)} to ${newVal}"
		cmds << configSetCmd(param, newVal)
		cmds << configGetCmd(param)
	}	
	return cmds
}

private hasPendingChange(param) {
	return (getParamIntVal(param) != getParamStoredIntVal(param))
}

void updateHealthCheckInterval() {
	def minReportingInterval = minimumReportingInterval
	
	if (state.minReportingInterval != minReportingInterval) {
		state.minReportingInterval = minReportingInterval
			
		// Set the Health Check interval so that it can be skipped twice plus 5 minutes.
		def checkInterval = ((minReportingInterval * 2) + (5 * 60))
		
		def eventMap = createEventMap("checkInterval", checkInterval, false)
		eventMap.data = [protocol: "zwave", hubHardwareId: device.hub.hardwareID]
		
		sendEvent(eventMap)
	}
}

def ping() {
	logDebug "Pinging device because it has not checked in"
	return [switchBinaryGetCmd()]
}


def on() {
	logTrace "Turning On"
	return delayBetweenCmds([
		switchBinarySetCmd(0xFF),
		switchBinaryGetCmd()
	])
}

def off() {
	if (disableSwitchSetting) {
    logTrace "Switch is disabled"
    } else {
		logTrace "Turning Off"
		return delayBetweenCmds([
			switchBinarySetCmd(0x00),
			switchBinaryGetCmd()
		])
    }
}

def refresh() {
	logTrace "Refreshing"
	return delayBetweenCmds([
		switchBinaryGetCmd(),
		meterGetCmd(meterEnergy),
		meterGetCmd(meterPower),
		meterGetCmd(meterVoltage),
		meterGetCmd(meterCurrent)
	])
}

private versionGetCmd() {
	return secureCmd(zwave.versionV1.versionGet())
}

private switchBinaryGetCmd() {
	return secureCmd(zwave.switchBinaryV1.switchBinaryGet())
}

private switchBinarySetCmd(val) {
	return secureCmd(zwave.switchBinaryV1.switchBinarySet(switchValue: val))
}

private configSetCmd(param, val) {
	return secureCmd(zwave.configurationV2.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: val))
}

private configGetCmd(param) {
	return secureCmd(zwave.configurationV2.configurationGet(parameterNumber: param.num))
}

private secureCmd(cmd) {
	if (zwaveInfo?.zw?.contains("s") || ("0x98" in device.rawDescription?.split(" "))) {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	}
	else {
		return cmd.format()
	}	
}

private delayBetweenCmds(cmds, delay=500) {
	return cmds ? delayBetween(cmds, delay) : []
}


def parse(String description) {	
	def result = []
	def cmd = zwave.parse(description, commandClassVersions)
	if (cmd) {
		result += zwaveEvent(cmd)		
	}
	else {
		log.warn "Unable to parse: $description"
	}
		
	if (!isDuplicateCommand(state.lastCheckinTime, 60000)) {
		state.lastCheckinTime = new Date().time
		result << createEvent(createEventMap("lastCheckin", convertToLocalTimeString(new Date()), false))
	}
	return result
}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapsulatedCmd = cmd.encapsulatedCommand(commandClassVersions)	
	
	def result = []
	if (encapsulatedCmd) {
		result += zwaveEvent(encapsulatedCmd)
	}
	else {
		log.warn "Unable to extract encapsulated cmd from $cmd"
	}
	return result
}

private getCommandClassVersions() {
	[
		0x20: 1,	// Basic
		0x25: 1,	// Switch Binary
		0x27: 1,	// All Switch
		0x2B: 1,	// Scene Activation
		0x2C: 1,	// Scene Actuator Configuration
		0x59: 1,	// AssociationGrpInfo
		0x5A: 1,	// DeviceResetLocally
		0x5E: 2,	// ZwaveplusInfo
		0x70: 2,	// Configuration
		0x72: 2,	// ManufacturerSpecific
		0x73: 1,	// Powerlevel
		0x7A: 2,	// Firmware Update Md (3)
		0x85: 2,	// Association
		0x86: 1,	// Version (2)
		0x98: 1		// Security
	]
}

def zwaveEvent(physicalgraph.zwave.commands.configurationv2.ConfigurationReport cmd) {	
	def val = cmd.scaledConfigurationValue
		
	def configParam = configParams.find { param ->
		param.num == cmd.parameterNumber
	}
	
	if (configParam) {
		def name = configParam.options?.find { it.value == val}?.key
		logDebug "${configParam.name}(#${configParam.num}) = ${name != null ? name : val} (${val})"
		state["configVal${cmd.parameterNumber}"] = val
	}	
	else {
		logDebug "Parameter ${cmd.parameterNumber} = ${val}"
	}	
	return []
}

def zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport cmd) {
	logTrace "VersionReport: ${cmd}"
	
	def version = "${cmd.applicationVersion}.${cmd.applicationSubVersion}"
	
	if (version != device.currentValue("firmwareVersion")) {
		logDebug "Firmware: ${version}"
		sendEvent(name: "firmwareVersion", value: version, displayed:false)
	}
	return []	
}

def zwaveEvent(physicalgraph.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
	logTrace "SwitchBinaryReport: ${cmd}"
	def result = []
	result << createSwitchEvent(cmd.value, "digital")
	return result
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
	logTrace "BasicReport: ${cmd}"
	def result = []
	result << createSwitchEvent(cmd.value, "physical")
	return result
}

private createSwitchEvent(value, type) {
	def eventVal = (value == 0xFF) ? "on" : "off"
	def map = createEventMap("switch", eventVal, null, "Switch is ${eventVal}")
	map.type = type
	return createEvent(map)
}

private createAccelerationEvent(val) {
	def deviceActive = (device.currentValue("acceleration") == "active")
	if (val > inactivePowerSetting &&  !deviceActive) {
		sendEvent(name:"acceleration", value:"active", displayed:false)
	}
	else if (val <= inactivePowerSetting && deviceActive){
		sendEvent(name:"acceleration", value:"inactive", displayed:false)
	}
}

private getFormattedDuration(duration, divisor, name) {
	if (divisor) {
		duration = roundTwoPlaces(duration / divisor)
	}	
	return "${duration} ${name}${duration == 1 ? '' : 's'}"
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
	logDebug "Unhandled zwaveEvent: $cmd"
	return []
}


// Configuration Parameters
private getConfigParams() {
	return [
		instantPowerValuesParam,
		instantPowerFrequencyParam,
		ledIndicatorParam,
	]
}

private getLedIndicatorParam() {
	return createConfigParamMap(83, "LED Power Indicator", 1, ["Follow the status of load (Energy mode)${defaultOptionSuffix}":0, "Follow the status (on/off) of load. Red LED turns off after 5 sec if no change (Momentary indicate mode)":1], "ledIndicator")
}

private getParamStoredIntVal(param) {
	return state["configVal${param.num}"]
}

private getParamIntVal(param) {
	return param.options ? convertOptionSettingToInt(param.options, param.val) : param.val
}

private createConfigParamMap(num, name, size, options, prefName, val=null) {
	if (val == null) {
		val = (settings?."${prefName}" ?: findDefaultOptionName(options))
	}
	return [
		num: num, 
		name: name, 
		size: size, 
		options: options, 
		prefName: prefName,
		val: val
	]
}

// Settings
private getEnergyPriceSetting() {
	return safeToDec(settings?.energyPrice, 0.12)
}

private getInactivePowerSetting() {
	return safeToInt(settings?.inactivePower, 0)
}

private getDebugOutputSetting() {
	return settings?.debugOutput != false
}

private getTraceOutputSetting() {
	return settings?.traceOutput != false
}

private getDisableSwitchSetting() {
	return settings?.disableSwitch != false
}

private getPercentageOptions(defaultVal=null, zeroName=null) {
	def options = [:]
	if (zeroName) {
		options["${zeroName}"] = 0
	}	
	for (int i = 1; i <= 5; i += 1) {
		options["${i}%"] = i
	}		
	for (int i = 10; i <= 100; i += 5) {
		options["${i}%"] = i
	}	
	return setDefaultOption(options, defaultVal)
}

private convertOptionSettingToInt(options, settingVal) {
	return safeToInt(options?.find { name, val -> "${settingVal}" == name }?.value, 0)
}

private setDefaultOption(options, defaultVal) {
	def name = options.find { key, val -> val == defaultVal }?.key
	if (name != null) {
		return changeOptionName(options, defaultVal, "${name}${defaultOptionSuffix}")
	}
	else {
		return options
	}	
}

private changeOptionName(options, optionVal, newName) {
	def result = [:]	
	options?.each { name, val ->
		if (val == optionVal) {
			name = "${newName}"
		}
		result["${name}"] = val
	}
	return result
}

private findDefaultOptionName(options) {
	def option = options?.find { name, val ->
		name?.contains("${defaultOptionSuffix}") 
	}
	return option?.key ?: ""
}

private getDefaultOptionSuffix() {
	return "   (Default)"
}

private createEventMap(name, value, displayed=null, desc=null, unit=null) {	
	desc = desc ?: "${name} is ${value}"
	
	def eventMap = [
		name: name,
		value: value,
		// isStateChange: true,
		displayed: (displayed == null ? ("${getAttrVal(name)}" != "${value}") : displayed)
	]
	
	if (unit) {
		eventMap.unit = unit
		desc = "${desc} ${unit}"
	}
	
	if (desc && eventMap.displayed) {
		logDebug desc
		eventMap.descriptionText = "${device.displayName} - ${desc}"
	}
	else {
		logTrace "Creating Event: ${eventMap}"
	}
	return eventMap
}

private getAttrVal(attrName) {
	try {
		return device?.currentValue("${attrName}")
	}
	catch (ex) {
		logTrace "$ex"
		return null
	}
}

private safeToInt(val, defaultVal=0) {
	return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

private safeToDec(val, defaultVal=0) {
	return "${val}"?.isBigDecimal() ? "${val}".toBigDecimal() : defaultVal
}

private roundTwoPlaces(val) {
	return Math.round(safeToDec(val) * 100) / 100
}

private convertToLocalTimeString(dt) {
	def timeZoneId = location?.timeZone?.ID
	if (timeZoneId) {
		return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
	}
	else {
		return "$dt"
	}	
}

private isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time) 
}

private logDebug(msg) {
	if (debugOutputSetting) {
		log.debug "$msg"
	}
}

private logTrace(msg) {
	if (traceOutputSetting) {
	log.trace "$msg"
    }
}