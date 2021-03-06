/**
 *  Sonos Service Manager
 *
 *  Author: SmartThings
 */
preferences {
	page(name:"sonosDiscovery", title:"Sonos Device Setup", content:"sonosDiscovery", refreshTimeout:5)
}
//PAGES
def sonosDiscovery()
{
	if(canInstallLabs())
	{
		int sonosRefreshCount = !state.sonosRefreshCount ? 0 : state.sonosRefreshCount as int
		state.sonosRefreshCount = sonosRefreshCount + 1
		def refreshInterval = 3

		def options = sonosesDiscovered() ?: []

		def numFound = options.size() ?: 0

		if(!state.subscribe) {
			log.trace "subscribe to location"
			subscribe(location, null, locationHandler, [filterEvents:false])
			state.subscribe = true
		}

		//sonos discovery request every 5 //25 seconds
		if((sonosRefreshCount % 8) == 0) {
			discoverSonoses()
		}

		//setup.xml request every 3 seconds except on discoveries
		if(((sonosRefreshCount % 1) == 0) && ((sonosRefreshCount % 8) != 0)) {
			verifySonosPlayer()
		}

		return dynamicPage(name:"sonosDiscovery", title:"Discovery Started!", nextPage:"", refreshInterval:refreshInterval, install:true, uninstall: true) {
			section("Please wait while we discover your Sonos. Discovery can take five minutes or more, so sit back and relax! Select your device below once discovered.") {
				input "selectedSonos", "enum", required:false, title:"Select Sonos (${numFound} found)", multiple:true, options:options
			}
		}
	}
	else
	{
		def upgradeNeeded = """To use SmartThings Labs, your Hub should be completely up to date.

To update your Hub, access Location Settings in the Main Menu (tap the gear next to your location name), select your Hub, and choose "Update Hub"."""

		return dynamicPage(name:"sonosDiscovery", title:"Upgrade needed!", nextPage:"", install:false, uninstall: true) {
			section("Upgrade") {
				paragraph "$upgradeNeeded"
			}
		}
	}
}

private discoverSonoses()
{
	//consider using other discovery methods
	sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:schemas-upnp-org:device:ZonePlayer:1", physicalgraph.device.Protocol.LAN))
}


private verifySonosPlayer() {
	def devices = getSonosPlayer().findAll { it?.value?.verified != true }

	if(devices) {
		log.warn "UNVERIFIED PLAYERS!: $devices"
	}

	devices.each {
		verifySonos((it?.value?.ip + ":" + it?.value?.port))
	}
}

private verifySonos(String deviceNetworkId) {

	log.trace "dni: $deviceNetworkId"
	String ip = getHostAddress(deviceNetworkId)

	log.trace "ip:" + ip

	sendHubCommand(new physicalgraph.device.HubAction("""GET /xml/device_description.xml HTTP/1.1\r\nHOST: $ip\r\n\r\n""", physicalgraph.device.Protocol.LAN, "${deviceNetworkId}"))
}

Map sonosesDiscovered() {
	def vsonoses = getVerifiedSonosPlayer()
	def map = [:]
	vsonoses.each {
		def value = "${it.value.name}"
		def key = it.value.ip + ":" + it.value.port
		map["${key}"] = value
	}
	map
}

def getSonosPlayer()
{
	state.sonoses = state.sonoses ?: [:]
}

def getVerifiedSonosPlayer()
{
	getSonosPlayer().findAll{ it?.value?.verified == true }
}

def installed() {
	log.trace "Installed with settings: ${settings}"
	initialize()}

def updated() {
	log.trace "Updated with settings: ${settings}"
	unschedule()
	initialize()
}

def uninstalled() {
	def devices = getChildDevices()
	log.trace "deleting ${devices.size()} Sonos"
	devices.each {
		deleteChildDevice(it.deviceNetworkId)
	}
}

def initialize() {
	// remove location subscription aftwards
	unsubscribe()
	state.subscribe = false

	unschedule()
	scheduleActions()

	if (selectedSonos) {
		addSonos()
	}

	scheduledActionsHandler()
}

def scheduledActionsHandler() {
	log.trace "scheduledActionsHandler()"
	syncDevices()
	refreshAll()
}

private scheduleActions() {
	def sec = Math.round(Math.floor(Math.random() * 60))
	def min = Math.round(Math.floor(Math.random() * 20))
	def cron = "$sec $min/20 * * * ?"
	log.trace "schedule('$cron', scheduledActionsHandler)"
	schedule(cron, scheduledActionsHandler)
}

private syncDevices() {
	log.trace "Doing Sonos Device Sync!"
	//runIn(300, "doDeviceSync" , [overwrite: false]) //schedule to run again in 5 minutes

	if(!state.subscribe) {
		subscribe(location, null, locationHandler, [filterEvents:false])
		state.subscribe = true
	}

	discoverSonoses()
}

private refreshAll(){
	log.trace "refreshAll()"
	childDevices*.refresh()
	log.trace "/refreshAll()"
}

def addSonos() {
	def players = getVerifiedSonosPlayer()
	def runSubscribe = false
	selectedSonos.each { dni ->
		def d = getChildDevice(dni)
		if(!d) {
			def newPlayer = players.find { (it.value.ip + ":" + it.value.port) == dni }
			log.trace "newPlayer = $newPlayer"
			log.trace "dni = $dni"
			d = addChildDevice("smartthings", "Sonos Player", dni, newPlayer?.value.hub, [label:"${newPlayer?.value.name} Sonos"])
			log.trace "created ${d.displayName} with id $dni"

			d.setModel(newPlayer?.value.model)
			log.trace "setModel to ${newPlayer?.value.model}"

			runSubscribe = true
		} else {
			log.trace "found ${d.displayName} with id $dni already exists"
		}
	}
}

def locationHandler(evt) {
	def description = evt.description
	def hub = evt?.hubId

	def parsedEvent = parseEventMessage(description)
	parsedEvent << ["hub":hub]

	if (parsedEvent?.ssdpTerm?.contains("urn:schemas-upnp-org:device:ZonePlayer:1"))
	{ //SSDP DISCOVERY EVENTS

		log.trace "sonos found"
		def sonoses = getSonosPlayer()

		if (!(sonoses."${parsedEvent.ssdpUSN.toString()}"))
		{ //sonos does not exist
			sonoses << ["${parsedEvent.ssdpUSN.toString()}":parsedEvent]
		}
		else
		{ // update the values

			log.trace "Device was already found in state..."

			def d = sonoses."${parsedEvent.ssdpUSN.toString()}"
			boolean deviceChangedValues = false

			if(d.ip != parsedEvent.ip || d.port != parsedEvent.port) {
				d.ip = parsedEvent.ip
				d.port = parsedEvent.port
				deviceChangedValues = true
				log.trace "Device's port or ip changed..."
			}

			if (deviceChangedValues) {
				def children = getChildDevices()
				children.each {
					if (it.getDeviceDataByName("mac") == parsedEvent.mac) {
						log.trace "updating dni for device ${it} with mac ${parsedEvent.mac}"
						it.setDeviceNetworkId((parsedEvent.ip + ":" + parsedEvent.port)) //could error if device with same dni already exists
					}
				}
			}
		}
	}
	else if (parsedEvent.headers && parsedEvent.body)
	{ // SONOS RESPONSES
		def headerString = new String(parsedEvent.headers.decodeBase64())
		def bodyString = new String(parsedEvent.body.decodeBase64())

		def type = (headerString =~ /Content-Type:.*/) ? (headerString =~ /Content-Type:.*/)[0] : null
		def body
		log.trace "SONOS REPONSE TYPE: $type"
		if (type?.contains("xml"))
		{ // description.xml response (application/xml)
			body = new XmlSlurper().parseText(bodyString)

			if (body?.device?.modelName?.text().startsWith("Sonos") && !body?.device?.modelName?.text().contains("Bridge") && !body?.device?.modelName?.text().contains("Sub"))
			{
				def sonoses = getSonosPlayer()
				def player = sonoses.find {it?.key?.contains(body?.device?.UDN?.text())}
				if (player)
				{
					player.value << [name:body?.device?.roomName?.text(),model:body?.device?.modelName?.text(), serialNumber:body?.device?.serialNum?.text(), verified: true]
				}
				else
				{
					log.error "/xml/device_description.xml returned a device that didn't exist"
				}
			}
		}
		else if(type?.contains("json"))
		{ //(application/json)
			body = new groovy.json.JsonSlurper().parseText(bodyString)
			log.trace "GOT JSON $body"
		}

	}
	else {
		log.trace "cp desc: " + description
		//log.trace description
	}
}

private def parseEventMessage(Map event) {
	//handles sonos attribute events
	return event
}

private def parseEventMessage(String description) {
	def event = [:]
	def parts = description.split(',')
	parts.each { part ->
		part = part.trim()
		if (part.startsWith('devicetype:')) {
			def valueString = part.split(":")[1].trim()
			event.devicetype = valueString
		}
		else if (part.startsWith('mac:')) {
			def valueString = part.split(":")[1].trim()
			if (valueString) {
				event.mac = valueString
			}
		}
		else if (part.startsWith('networkAddress:')) {
			def valueString = part.split(":")[1].trim()
			if (valueString) {
				event.ip = valueString
			}
		}
		else if (part.startsWith('deviceAddress:')) {
			def valueString = part.split(":")[1].trim()
			if (valueString) {
				event.port = valueString
			}
		}
		else if (part.startsWith('ssdpPath:')) {
			def valueString = part.split(":")[1].trim()
			if (valueString) {
				event.ssdpPath = valueString
			}
		}
		else if (part.startsWith('ssdpUSN:')) {
			part -= "ssdpUSN:"
			def valueString = part.trim()
			if (valueString) {
				event.ssdpUSN = valueString
			}
		}
		else if (part.startsWith('ssdpTerm:')) {
			part -= "ssdpTerm:"
			def valueString = part.trim()
			if (valueString) {
				event.ssdpTerm = valueString
			}
		}
		else if (part.startsWith('headers')) {
			part -= "headers:"
			def valueString = part.trim()
			if (valueString) {
				event.headers = valueString
			}
		}
		else if (part.startsWith('body')) {
			part -= "body:"
			def valueString = part.trim()
			if (valueString) {
				event.body = valueString
			}
		}
	}

	event
}


/////////CHILD DEVICE METHODS
def parse(childDevice, description) {
	def parsedEvent = parseEventMessage(description)

	if (parsedEvent.headers && parsedEvent.body) {
		def headerString = new String(parsedEvent.headers.decodeBase64())
		def bodyString = new String(parsedEvent.body.decodeBase64())
		log.trace "parse() - ${bodyString}"

		def body = new groovy.json.JsonSlurper().parseText(bodyString)
	} else {
		log.trace "parse - got something other than headers,body..."
		return []
	}
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private getHostAddress(d) {
	def parts = d.split(":")
	def ip = convertHexToIP(parts[0])
	def port = convertHexToInt(parts[1])
	return ip + ":" + port
}

private Boolean canInstallLabs()
{
	return hasAllHubsOver("000.011.00603")
}

private Boolean hasAllHubsOver(String desiredFirmware)
{
	return realHubFirmwareVersions.every { fw -> fw >= desiredFirmware }
}

private List getRealHubFirmwareVersions()
{
	return location.hubs*.firmwareVersionString.findAll { it }
}
