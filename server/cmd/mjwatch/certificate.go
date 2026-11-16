package main

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"os"
	"path/filepath"
)

// The public CA authenticates itself against the fingerprint in an invitation.
// Existing clients still validate the same leaf with their saved CA.
func loadServerCertificate(certificate, privateKey string) (*tls.Certificate, error) {
	cert, err := tls.LoadX509KeyPair(certificate, privateKey)
	if err != nil {
		return nil, err
	}
	raw, err := os.ReadFile(filepath.Join(filepath.Dir(certificate), "ca.crt"))
	if os.IsNotExist(err) {
		return &cert, nil
	}
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, fmt.Errorf("invalid public CA certificate")
	}
	ca, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, err
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return nil, err
	}
	if leaf.CheckSignatureFrom(ca) == nil && !bytes.Equal(leaf.Raw, ca.Raw) {
		found := false
		for _, existing := range cert.Certificate {
			if bytes.Equal(existing, ca.Raw) {
				found = true
			}
		}
		if !found {
			cert.Certificate = append(cert.Certificate, ca.Raw)
		}
	}
	return &cert, nil
}
