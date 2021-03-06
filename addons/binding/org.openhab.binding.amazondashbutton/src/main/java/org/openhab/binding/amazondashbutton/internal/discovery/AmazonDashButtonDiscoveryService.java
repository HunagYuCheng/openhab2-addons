/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.amazondashbutton.internal.discovery;

import static org.openhab.binding.amazondashbutton.AmazonDashButtonBindingConstants.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.amazondashbutton.internal.capturing.PacketCapturingHandler;
import org.openhab.binding.amazondashbutton.internal.capturing.PacketCapturingService;
import org.openhab.binding.amazondashbutton.internal.pcap.PcapNetworkInterfaceListener;
import org.openhab.binding.amazondashbutton.internal.pcap.PcapNetworkInterfaceService;
import org.openhab.binding.amazondashbutton.internal.pcap.PcapNetworkInterfaceWrapper;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.util.MacAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

/**
 * The {@link AmazonDashButtonDiscoveryService} is responsible for discovering Amazon Dash Buttons. It does so by
 * capturing ARP and BOOTP requests from all available network devices.
 *
 * While scanning the user has to press the button in order to send an ARP and BOOTP request packet. The
 * {@link AmazonDashButtonDiscoveryService} captures this packet and checks the device's MAC address which sent the
 * request against a static list of vendor prefixes ({@link #vendorPrefixes}).
 *
 * If an Amazon MAC address is detected a {@link DiscoveryResult} is built and passed to
 * {@link #thingDiscovered(DiscoveryResult)}.
 *
 * @author Oliver Libutzki - Initial contribution
 *
 */
public class AmazonDashButtonDiscoveryService extends AbstractDiscoveryService implements PcapNetworkInterfaceListener {

    private static final int DISCOVER_TIMEOUT_SECONDS = 30;

    private final Logger logger = LoggerFactory.getLogger(AmazonDashButtonDiscoveryService.class);

    /**
     * The Amazon Dash button vendor prefixes
     */
    // @formatter:off
    private static final Set<String> vendorPrefixes = Sets.newHashSet(
            "44:65:0D",
            "50:F5:DA",
            "84:D6:D0",
            "34:D2:70",
            "F0:D2:F1",
            "88:71:E5",
            "74:C2:46",
            // This is an Amazon MAC address, but it has been used by my Fire TV...
            // "F0:27:2D",
            "0C:47:C9",
            "A0:02:DC",
            "74:75:48",
            "AC:63:BE",
            "FC:A6:67"
        );
    // @formatter:on

    /**
     * Returns true if the passed macAddress is an Amazon MAC address.
     *
     * @param macAddress
     * @return
     */
    private static boolean isAmazonVendor(String macAddress) {
        String vendorPrefix = macAddress.substring(0, 8).toUpperCase();
        return vendorPrefixes.contains(vendorPrefix);
    }

    private final Map<PcapNetworkInterfaceWrapper, PacketCapturingService> packetCapturingServices = new ConcurrentHashMap<>();

    private boolean explicitScanning = false;
    private boolean backgroundScanning = false;

    public AmazonDashButtonDiscoveryService() {
        super(Collections.singleton(DASH_BUTTON_THING_TYPE), DISCOVER_TIMEOUT_SECONDS, false);
    }

    @Override
    protected void startScan() {
        explicitScanning = true;
        updateListenerRegistry();
    }

    @Override
    protected synchronized void stopScan() {
        explicitScanning = false;
        updateListenerRegistry();
        super.stopScan();
    }

    @Override
    protected void startBackgroundDiscovery() {
        backgroundScanning = true;
        updateListenerRegistry();
    }

    @Override
    protected void stopBackgroundDiscovery() {
        backgroundScanning = false;
        updateListenerRegistry();
    }

    @Override
    public void onPcapNetworkInterfaceAdded(final PcapNetworkInterfaceWrapper networkInterface) {
        startCapturing(networkInterface);
    }

    @Override
    public void onPcapNetworkInterfaceRemoved(PcapNetworkInterfaceWrapper networkInterface) {
        stopCapturing(networkInterface);
    }

    private void updateListenerRegistry() {
        boolean shouldListen = explicitScanning || backgroundScanning;
        if (shouldListen) {
            PcapNetworkInterfaceService.instance().registerListener(this);
            // Start capturing for all network interfaces
            final Set<PcapNetworkInterfaceWrapper> networkInterfaces = PcapNetworkInterfaceService.instance()
                    .getNetworkInterfaces();
            for (PcapNetworkInterfaceWrapper pcapNetworkInterface : networkInterfaces) {
                startCapturing(pcapNetworkInterface);
            }
        } else {
            PcapNetworkInterfaceService.instance().unregisterListener(this);
            // Stop capturing for all network interfaces
            final Set<PcapNetworkInterfaceWrapper> networkInterfaces = packetCapturingServices.keySet();
            for (PcapNetworkInterfaceWrapper pcapNetworkInterface : networkInterfaces) {
                stopCapturing(pcapNetworkInterface);
            }
        }
    }

    /**
     * Stops capturing for packets for the given {@link PcapNetworkInterface}.
     *
     * @param pcapNetworkInterface The {@link PcapNetworkInterface} the capturing should be stopped for.
     */
    private void stopCapturing(final PcapNetworkInterfaceWrapper pcapNetworkInterface) {
        final PacketCapturingService packetCapturingService = packetCapturingServices.remove(pcapNetworkInterface);
        final String interfaceName = pcapNetworkInterface.getName();
        if (packetCapturingService != null) {
            packetCapturingService.stopCapturing();
            logger.debug("Stopped capturing for {}.", interfaceName);
        } else {
            logger.warn("No active PacketCapturingService registered for {}.", interfaceName);
        }
    }

    /**
     * Starts capturing for packets for the given {@link PcapNetworkInterface}. If the network interface is already
     * captured this method returns without doing anything.
     *
     * @param pcapNetworkInterface The {@link PcapNetworkInterface} to be captured
     */
    private void startCapturing(final PcapNetworkInterfaceWrapper pcapNetworkInterface) {
        if (packetCapturingServices.containsKey(pcapNetworkInterface)) {
            // We already have a tracker
            return;
        }

        PacketCapturingService packetCapturingService = new PacketCapturingService(pcapNetworkInterface);

        packetCapturingServices.put(pcapNetworkInterface, packetCapturingService);
        final String interfaceName = pcapNetworkInterface.getName();
        final boolean capturingStarted = packetCapturingService.startCapturing(new PacketCapturingHandler() {

            @Override
            public void packetCaptured(MacAddress macAddress) {
                String macAdressString = macAddress.toString();

                if (isAmazonVendor(macAdressString)) {
                    logger.debug("Captured a packet from {} which seems to be sent from an Amazon Dash Button device.",
                            macAdressString);
                    ThingUID dashButtonThing = new ThingUID(DASH_BUTTON_THING_TYPE, macAdressString.replace(":", "-"));
                    // @formatter:off
                    DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(dashButtonThing)
                            .withLabel("Dash Button")
                            .withRepresentationProperty(macAdressString)
                            .withProperty(PROPERTY_MAC_ADDRESS, macAdressString)
                            .withProperty(PROPERTY_NETWORK_INTERFACE_NAME, interfaceName)
                            .withProperty(PROPERTY_PACKET_INTERVAL, BigDecimal.valueOf(5000))
                            .build();
                    // @formatter:on
                    thingDiscovered(discoveryResult);
                } else {
                    logger.trace(
                            "Captured a packet from {} which is ignored as it's not on the list of supported vendor prefixes.",
                            macAdressString);
                }
            }
        });
        if (capturingStarted) {
            logger.debug("Started capturing for {}.", interfaceName);
        }
    }

}
