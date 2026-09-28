package com.lukestudio.fileencryptor2;

import java.io.*;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Compatibility implementation recovered from S File Encryptor 1.3.
 * Format: salt(16) || IV(16) || AES-CBC ciphertext || HMAC-SHA256(32).
 */
public final class CryptoEngine {
    public static final int HEADER_SIZE = 32;
    public static final int HMAC_SIZE = 32;
    private static final int ITERATIONS = 100000;
    private static final int BUFFER_SIZE = 4096;

    private CryptoEngine() {}

    public interface Progress { void onProgress(int percent); }

    private static byte[][] derive(char[] password, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 512);
        byte[] full = f.generateSecret(spec).getEncoded();
        Arrays.fill(password, '\0');
        byte[] aes = Arrays.copyOfRange(full, 0, 32);
        byte[] hmac = Arrays.copyOfRange(full, 32, 64);
        Arrays.fill(full, (byte) 0);
        spec.clearPassword();
        return new byte[][] { aes, hmac };
    }

    public static void encrypt(InputStream input, OutputStream output, char[] password,
                               long totalBytes, Progress progress) throws Exception {
        byte[] salt = new byte[16], iv = new byte[16];
        byte[][] keys = null;
        try {
            SecureRandom sr = new SecureRandom(); sr.nextBytes(salt); sr.nextBytes(iv);
            keys = derive(password, salt);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys[0], "AES"), new IvParameterSpec(iv));
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(keys[1], "HmacSHA256"));
            mac.update(salt); mac.update(iv);
            output.write(salt); output.write(iv);
            OutputStream macOut = new OutputStream() {
                public void write(int b) throws IOException { mac.update((byte)b); output.write(b); }
                public void write(byte[] b,int off,int len) throws IOException { mac.update(b,off,len); output.write(b,off,len); }
                public void flush() throws IOException { output.flush(); }
                public void close() throws IOException { output.flush(); }
            };
            CipherOutputStream cipherOut = new CipherOutputStream(macOut, cipher);
            byte[] buffer = new byte[BUFFER_SIZE]; long done=0; int n;
            while ((n=input.read(buffer)) != -1) {
                cipherOut.write(buffer,0,n); done += n;
                if (progress != null && totalBytes > 0) progress.onProgress((int)Math.min(100, done*100/totalBytes));
            }
            cipherOut.close();
            output.write(mac.doFinal()); output.flush();
            if (progress != null) progress.onProgress(100);
        } finally { wipe(keys); Arrays.fill(salt,(byte)0); Arrays.fill(iv,(byte)0); }
    }

    /** Verifies the HMAC using one fresh stream, then decrypts using a second fresh stream. */
    public static void decrypt(InputStream verifyInput, InputStream decryptInput, OutputStream output,
                               char[] password, long totalBytes, Progress progress) throws Exception {
        if (totalBytes < 64) throw new CorruptFileException();
        byte[] salt=new byte[16], iv=new byte[16], stored=new byte[32]; byte[][] keys=null;
        try {
            readFully(verifyInput,salt); readFully(verifyInput,iv);
            long encryptedLength=totalBytes-64;
            keys=derive(password,salt);
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(keys[1],"HmacSHA256"));
            mac.update(salt); mac.update(iv);
            byte[] buffer=new byte[BUFFER_SIZE]; long left=encryptedLength; int n;
            while(left>0){
                n=verifyInput.read(buffer,0,(int)Math.min(buffer.length,left));
                if(n<0) throw new CorruptFileException();
                mac.update(buffer,0,n); left-=n;
                if(progress!=null && encryptedLength>0) progress.onProgress((int)Math.min(100,left==0?50:(encryptedLength-left)*50/encryptedLength));
            }
            readFully(verifyInput,stored);
            if(!MessageDigest.isEqual(mac.doFinal(),stored)) throw new WrongPasswordException();

            readFully(decryptInput,salt); readFully(decryptInput,iv);
            Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(keys[0],"AES"),new IvParameterSpec(iv));
            InputStream limited=new LimitedInputStream(decryptInput,encryptedLength);
            CipherInputStream cis=new CipherInputStream(new BufferedInputStream(limited),cipher);
            long done=0;
            while((n=cis.read(buffer))!=-1){ output.write(buffer,0,n); done+=n; if(progress!=null && encryptedLength>0) progress.onProgress(50+(int)Math.min(50,done*50/encryptedLength)); }
            cis.close(); output.flush(); if(progress!=null)progress.onProgress(100);
        } finally { wipe(keys); Arrays.fill(salt,(byte)0); Arrays.fill(iv,(byte)0); Arrays.fill(stored,(byte)0); }
    }

    public static byte[] decryptBytes(byte[] encrypted, char[] password) throws Exception {
        if(encrypted.length<64) throw new CorruptFileException();
        byte[] salt=Arrays.copyOfRange(encrypted,0,16), iv=Arrays.copyOfRange(encrypted,16,32);
        byte[] stored=Arrays.copyOfRange(encrypted,encrypted.length-32,encrypted.length); byte[][] keys=derive(password,salt);
        try{
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(keys[1],"HmacSHA256")); mac.update(salt);mac.update(iv);mac.update(encrypted,32,encrypted.length-64);
            if(!MessageDigest.isEqual(mac.doFinal(),stored)) throw new WrongPasswordException();
            Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(keys[0],"AES"),new IvParameterSpec(iv));
            return c.doFinal(encrypted,32,encrypted.length-64);
        }finally{wipe(keys);Arrays.fill(salt,(byte)0);Arrays.fill(iv,(byte)0);Arrays.fill(stored,(byte)0);}
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream in,long remaining){super(in);this.remaining=remaining;}
        public int read() throws IOException { if(remaining<=0)return -1; int v=super.read(); if(v>=0)remaining--; return v; }
        public int read(byte[] b,int off,int len)throws IOException { if(remaining<=0)return -1; len=(int)Math.min(len,remaining); int n=super.read(b,off,len); if(n>0)remaining-=n; return n; }
    }

    private static void readFully(InputStream in, byte[] b)throws IOException{int p=0,n;while(p<b.length&&(n=in.read(b,p,b.length-p))>0)p+=n;if(p!=b.length)throw new EOFException();}
    private static void wipe(byte[][] k){if(k!=null)for(byte[] a:k)if(a!=null)Arrays.fill(a,(byte)0);}
    public static class WrongPasswordException extends GeneralSecurityException { public WrongPasswordException(){super("Senha errada ou arquivo alterado!");} }
    public static class CorruptFileException extends IOException { public CorruptFileException(){super("Arquivo corrompido!");} }
}
