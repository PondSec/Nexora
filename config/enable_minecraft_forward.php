<?php
require_once('/usr/local/etc/inc/util.inc');
require_once('/usr/local/etc/inc/config.inc');
require_once('/usr/local/etc/inc/filter.inc');

global $config;
$natDescription = 'Nexora Minecraft TCP to DMZ Nginx stream';
$filterDescription = 'Allow Nexora Minecraft TCP from WAN to DMZ Nginx';

$natExists = false;
foreach (($config['nat']['rule'] ?? []) as $rule) {
    $natExists = $natExists || (($rule['descr'] ?? '') === $natDescription);
}
$filterExists = false;
foreach (($config['filter']['rule'] ?? []) as $rule) {
    $filterExists = $filterExists || (($rule['descr'] ?? '') === $filterDescription);
}

if (!$natExists) {
    $config['nat']['rule'][] = [
    'sequence' => '400',
    'interface' => 'wan',
    'ipprotocol' => 'inet',
    'protocol' => 'tcp',
    'source' => ['any' => '1'],
    'destination' => ['network' => 'wanip', 'port' => '25565', 'not' => '0'],
    'target' => '192.168.30.3',
    'local-port' => '25565',
    'descr' => $natDescription,
    'associated-rule' => '',
    ];
}

if (!$filterExists) {
    $config['filter']['rule'][] = [
    'type' => 'pass',
    'interface' => 'wan',
    'ipprotocol' => 'inet',
    'statetype' => 'keep state',
    'descr' => $filterDescription,
    'direction' => 'in',
    'log' => '1',
    'quick' => '1',
    'protocol' => 'tcp',
    'source' => ['any' => '1'],
    'destination' => ['address' => '192.168.30.3/32', 'port' => '25565'],
    ];
}

if ((!$natExists || !$filterExists) && write_config('Add Nexora Minecraft TCP forward to DMZ Nginx') === false) {
    fwrite(STDERR, "OPNsense configuration write failed.\n");
    exit(1);
}
filter_configure();
echo "Nexora TCP 25565 NAT and WAN pass rule are configured; firewall reload requested.\n";
