package anet

import "net"

// Interfaces returns a list of the system's network interfaces.
func Interfaces() ([]net.Interface, error) {
	return net.Interfaces()
}

// InterfaceAddrs returns a list of the system's unicast interface addresses.
func InterfaceAddrs() ([]net.Addr, error) {
	return net.InterfaceAddrs()
}

// InterfaceAddrsByInterface returns addresses for a specific interface.
func InterfaceAddrsByInterface(ifi *net.Interface) ([]net.Addr, error) {
	return ifi.Addrs()
}

// SetAndroidVersion is kept for compatibility with github.com/wlynxg/anet.
func SetAndroidVersion(version uint) {}
