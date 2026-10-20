let currentSource = 'cellular';
let selectedDeviceIp = null;
let selectedDeviceMac = '';
let selectedDeviceIface = '';
let selectedDeviceBytes = 0;

function onSourceDropdownChange() {
    const select = document.getElementById('sourceSelect');
    if (select) {
        currentSource = select.value;
    }
}

function openInstructionsModal() {
    const modal = document.getElementById('instructionsModal');
    if (modal) modal.style.display = 'flex';
}

function closeInstructionsModal() {
    const modal = document.getElementById('instructionsModal');
    if (modal) modal.style.display = 'none';
}

async function fetchStatus() {
    try {
        let apiUrl = window.location.protocol.startsWith('http') ? '/api/status' : 'http://127.0.0.1:8081/api/status';
        if (selectedDeviceIp) {
            apiUrl += `?clientIp=${encodeURIComponent(selectedDeviceIp)}`;
        }

        const response = await fetch(apiUrl);
        if (!response.ok) throw new Error('HTTP error');
        const data = await response.json();

        const proxyIp = data.proxyIp || '192.168.49.1';
        const proxyPort = data.proxyPort || 8080;

        // Status & Overview
        document.getElementById('statusText').innerText = data.status === 'running' ? 'Active' : 'Stopped';
        document.getElementById('statusDot').className = data.status === 'running' ? 'dot active' : 'dot';
        document.getElementById('bytesTransferred').innerText = formatBytes(data.bytesTransferred);

        // Update Source Select Dropdown value & option text
        const sourceSelect = document.getElementById('sourceSelect');
        if (sourceSelect) {
            const activeVal = data.netShareMode === 'DIRECT_CELLULAR' ? 'cellular' : 'wifi';
            sourceSelect.value = activeVal;
        }

        const cellularOptionLabel = document.getElementById('cellularOptionLabel');
        if (cellularOptionLabel) {
            cellularOptionLabel.innerText = `📡 Cellular Data (Mobile 4G/5G) - Otether Proxy (${proxyIp}:${proxyPort})`;
        }

        const wifiOptionLabel = document.getElementById('wifiOptionLabel');
        if (wifiOptionLabel) {
            wifiOptionLabel.innerText = `📶 Wi-Fi Repeater Mode - Otether Proxy (${proxyIp}:${proxyPort})`;
        }

        // Dynamic Proxy IP & URL updates across HTML
        document.querySelectorAll('.proxyIpText').forEach(el => {
            el.innerText = proxyIp;
        });
        document.querySelectorAll('.webPanelUrl').forEach(el => {
            el.innerText = `http://${proxyIp}:8081`;
        });

        // Active NetShare Mode Badge
        const modeBadge = document.getElementById('activeModeBadge');
        if (modeBadge) {
            if (data.netShareMode === 'DIRECT_CELLULAR') {
                modeBadge.innerHTML = `⚡ <strong>Cellular Data Sharing Active</strong> (Proxy Required: ${proxyIp}:${proxyPort})`;
            } else if (data.netShareMode === 'DIRECT_WIFI_DUAL') {
                modeBadge.innerHTML = `🚀 <strong>Direct Hardware Wi-Fi Extension Active</strong> (Proxy Required: ${proxyIp}:${proxyPort})`;
            } else {
                modeBadge.innerHTML = `🔄 <strong>Wi-Fi Repeater Mode Active</strong> (Proxy Required: ${proxyIp}:${proxyPort})`;
            }
        }

        // AP SSID & Password
        if (data.ssid) {
            document.getElementById('apSsid').innerText = data.ssid;
            const apSsidModal = document.getElementById('apSsidModal');
            if (apSsidModal) apSsidModal.innerText = data.ssid;
        }
        if (data.password !== undefined) {
            valText = data.password || 'Open Network (No Password)';
            document.getElementById('apPassword').innerText = valText;
            const apPasswordModal = document.getElementById('apPasswordModal');
            if (apPasswordModal) apPasswordModal.innerText = valText;
        }

        // Hotspot Error Banner
        const errorBanner = document.getElementById('errorBanner');
        if (data.hotspotError && data.hotspotError.length > 0) {
            document.getElementById('errorText').innerText = data.hotspotError;
            errorBanner.style.display = 'block';
        } else {
            errorBanner.style.display = 'none';
        }

        // Connected Clients
        const clients = data.clients || [];
        const packets = data.packets || [];
        document.getElementById('clientCount').innerText = `${clients.length} Device${clients.length === 1 ? '' : 's'}`;
        document.getElementById('packetCount').innerText = `${packets.length} Events`;
        renderClientsTable(clients);

        // If inspecting a specific device, update device stats
        if (selectedDeviceIp) {
            const currentDev = clients.find(c => c.ip === selectedDeviceIp);
            if (currentDev) {
                document.getElementById('detailBytes').innerText = formatBytes(currentDev.totalBytes);
            }
            renderDeviceDestinationsTable(data.destinations || []);
            renderDevicePacketsTable(packets);
        } else {
            renderDestinationsTable(data.destinations || []);
            renderPacketsTable(packets);
        }

    } catch (e) {
        document.getElementById('statusText').innerText = 'Stopped';
        document.getElementById('statusDot').className = 'dot';
        document.getElementById('bytesTransferred').innerText = '0 Bytes';
        document.getElementById('clientCount').innerText = '0 Devices';
        document.getElementById('packetCount').innerText = '0 Events';
    }
}

async function clearLogs() {
    try {
        const clearUrl = window.location.protocol.startsWith('http') ? '/api/clear-logs' : 'http://127.0.0.1:8081/api/clear-logs';
        await fetch(clearUrl);
        fetchStatus();
    } catch (e) {
        console.error('Failed to clear logs', e);
    }
}

function inspectDevice(ip, mac, iface, bytes) {
    selectedDeviceIp = ip;
    selectedDeviceMac = mac;
    selectedDeviceIface = iface;
    selectedDeviceBytes = bytes;

    document.getElementById('mainOverview').style.display = 'none';
    document.getElementById('deviceDetailView').style.display = 'block';

    document.getElementById('deviceDetailTitle').innerText = `Device Traffic: ${ip}`;
    document.getElementById('detailIp').innerText = ip;
    document.getElementById('detailMac').innerText = mac;
    document.getElementById('detailIface').innerText = iface;
    document.getElementById('detailBytes').innerText = formatBytes(bytes);

    fetchStatus();
}

function closeDeviceDetail() {
    selectedDeviceIp = null;
    document.getElementById('deviceDetailView').style.display = 'none';
    document.getElementById('mainOverview').style.display = 'block';
    fetchStatus();
}

function renderDestinationsTable(destinations) {
    const tbody = document.getElementById('destinationsTableBody');
    if (!tbody) return;
    if (destinations.length === 0) {
        tbody.innerHTML = '<tr><td colspan="2" class="empty-msg">No domain traffic recorded yet.</td></tr>';
        return;
    }

    tbody.innerHTML = destinations.map(d => `
        <tr>
            <td><strong>${escapeHtml(d.domain)}</strong></td>
            <td><code style="color:#ff1744; font-weight:bold;">${formatBytes(d.bytes)}</code></td>
        </tr>
    `).join('');
}

function renderDeviceDestinationsTable(destinations) {
    const tbody = document.getElementById('deviceDestinationsBody');
    if (!tbody) return;
    if (destinations.length === 0) {
        tbody.innerHTML = '<tr><td colspan="2" class="empty-msg">No traffic recorded for this device.</td></tr>';
        return;
    }

    tbody.innerHTML = destinations.map(d => `
        <tr>
            <td><strong>${escapeHtml(d.domain)}</strong></td>
            <td><code style="color:#ff1744; font-weight:bold;">${formatBytes(d.bytes)}</code></td>
        </tr>
    `).join('');
}

function renderClientsTable(clients) {
    const tbody = document.getElementById('clientsTableBody');
    if (!tbody) return;
    if (clients.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="empty-msg">No connected devices detected.</td></tr>';
        return;
    }

    tbody.innerHTML = clients.map(client => `
        <tr>
            <td><strong>${escapeHtml(client.ip)}</strong></td>
            <td><code>${escapeHtml(client.mac)}</code></td>
            <td>${escapeHtml(client.iface)}</td>
            <td><code style="color:#ff1744; font-weight:bold;">${formatBytes(client.totalBytes || 0)}</code></td>
            <td>
                <button onclick="inspectDevice('${escapeHtml(client.ip)}', '${escapeHtml(client.mac)}', '${escapeHtml(client.iface)}', ${client.totalBytes || 0})" class="btn-inspect">
                    🔍 Inspect Device
                </button>
            </td>
        </tr>
    `).join('');
}

function renderPacketsTable(packets) {
    const tbody = document.getElementById('packetsTableBody');
    if (!tbody) return;
    if (packets.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty-msg">Waiting for active traffic stream...</td></tr>';
        return;
    }

    const reversedPackets = [...packets].reverse();

    tbody.innerHTML = reversedPackets.map(p => {
        const protoClass = getProtocolClass(p.protocol);
        return `
            <tr>
                <td>${p.id}</td>
                <td><code>${escapeHtml(p.time)}</code></td>
                <td>${escapeHtml(p.src)}</td>
                <td>${escapeHtml(p.dst)}</td>
                <td><span class="protocol-badge ${protoClass}">${escapeHtml(p.protocol)}</span></td>
                <td>${p.length} B</td>
                <td><code>${escapeHtml(p.info)}</code></td>
            </tr>
        `;
    }).join('');
}

function renderDevicePacketsTable(packets) {
    const tbody = document.getElementById('devicePacketsBody');
    if (!tbody) return;
    if (packets.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty-msg">No packets recorded for this device yet.</td></tr>';
        return;
    }

    const reversedPackets = [...packets].reverse();

    tbody.innerHTML = reversedPackets.map(p => {
        const protoClass = getProtocolClass(p.protocol);
        return `
            <tr>
                <td>${p.id}</td>
                <td><code>${escapeHtml(p.time)}</code></td>
                <td>${escapeHtml(p.src)}</td>
                <td>${escapeHtml(p.dst)}</td>
                <td><span class="protocol-badge ${protoClass}">${escapeHtml(p.protocol)}</span></td>
                <td>${p.length} B</td>
                <td><code>${escapeHtml(p.info)}</code></td>
            </tr>
        `;
    }).join('');
}

function getProtocolClass(protocol) {
    if (protocol.includes('HTTPS') || protocol.includes('CONNECT')) return 'proto-https';
    if (protocol.includes('HTTP')) return 'proto-http';
    if (protocol.includes('UDP')) return 'proto-udp';
    return 'proto-tcp';
}

function formatBytes(bytes, decimals = 2) {
    if (!bytes || bytes === 0) return '0 Bytes';
    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

setInterval(fetchStatus, 2000);
fetchStatus();
