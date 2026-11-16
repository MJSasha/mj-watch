package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestCertificateOffersPublicCAForPinnedInvitationBootstrap(t *testing.T) {
	dir := t.TempDir()
	caKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	ca := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "fixture CA"}, IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	caDER, _ := x509.CreateCertificate(rand.Reader, ca, ca, &caKey.PublicKey, caKey)
	leafKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	leaf := &x509.Certificate{SerialNumber: big.NewInt(2), DNSNames: []string{"localhost"}, KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, NotBefore: ca.NotBefore, NotAfter: ca.NotAfter}
	leafDER, _ := x509.CreateCertificate(rand.Reader, leaf, ca, &leafKey.PublicKey, caKey)
	keyDER, _ := x509.MarshalECPrivateKey(leafKey)
	certFile, keyFile := filepath.Join(dir, "tls.crt"), filepath.Join(dir, "tls.key")
	write := func(path, kind string, data []byte) {
		t.Helper()
		if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: kind, Bytes: data}), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write(certFile, "CERTIFICATE", leafDER)
	write(keyFile, "EC PRIVATE KEY", keyDER)
	cert, err := loadServerCertificate(certFile, keyFile)
	if err != nil || len(cert.Certificate) != 1 {
		t.Fatal("public PKI must work without private CA", err)
	}
	write(filepath.Join(dir, "ca.crt"), "CERTIFICATE", caDER)
	cert, err = loadServerCertificate(certFile, keyFile)
	if err != nil || len(cert.Certificate) != 2 || !bytes.Equal(cert.Certificate[0], leafDER) || !bytes.Equal(cert.Certificate[1], caDER) {
		t.Fatal("public CA missing or leaf replaced", err)
	}
	combined := append(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: leafDER}), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER})...)
	os.WriteFile(certFile, combined, 0600)
	cert, err = loadServerCertificate(certFile, keyFile)
	if err != nil || len(cert.Certificate) != 2 {
		t.Fatal("CA duplicated", err)
	}
}
