// Código de exemplo
// Extraído do app S File Encryptor
// Apenas para fins educacionais


// Encrypt

new AsyncTask<Void, Integer, Void>() {
    private Mac macFinal;
    private FileOutputStream fosFinal;

    @Override
    protected void onPreExecute() {
        cd.show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ent = "CRIPTOGRAFANDO...";
        PG1.setProgress(0);
        TX1.setText("0%");
    }

    @Override
    protected Void doInBackground(Void... voids) {
        byte[] salt = null;
        byte[] ivBytes = null;
        byte[] aesKeyBytes = null;
        byte[] hmacKeyBytes = null;
        byte[] fullKey = null;
        char[] passChars = null;

        try {
            SecureRandom sr = new SecureRandom();
            salt = new byte[16];
            ivBytes = new byte[16];
            sr.nextBytes(salt);
            sr.nextBytes(ivBytes);

            Editable editable = pass.getText();

            // Copiar senha para char[]
            passChars = new char[editable.length()];
            editable.getChars(0, editable.length(), passChars, 0);

            // Limpar Editable o quanto antes (na UI thread pode ser melhor, mas já ajuda)
            // Pode-se usar post para isso, mas aqui simplifico
            pass.getText().clear();

            // Gerar chave com PBKDF2
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(passChars, salt, 100000, 512);
            SecretKey tmp = factory.generateSecret(spec);
            fullKey = tmp.getEncoded();

            // Limpar array de senha imediatamente após uso
            Arrays.fill(passChars, '\0');
            passChars = null; // ajuda GC

            // Separar chaves
            aesKeyBytes = Arrays.copyOfRange(fullKey, 0, 32);
            hmacKeyBytes = Arrays.copyOfRange(fullKey, 32, 64);

            // Limpar fullKey logo após copiar chaves
            Arrays.fill(fullKey, (byte) 0);
            fullKey = null;

            SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            SecretKeySpec hmacKey = new SecretKeySpec(hmacKeyBytes, "HmacSHA256");

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(ivBytes));

            macFinal = Mac.getInstance("HmacSHA256");
            macFinal.init(hmacKey);
            macFinal.update(salt);
            macFinal.update(ivBytes);

            File inputFile = new File(pathIn);
            String outputPath = pathOut.endsWith(".aes") ? pathOut : pathOut + ".aes";

            try (
                FileInputStream fis = new FileInputStream(inputFile);
                FileOutputStream fos = new FileOutputStream(outputPath)
            ) {
                fosFinal = fos;

                fos.write(salt);
                fos.write(ivBytes);

                OutputStream hmacOutputStream = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        macFinal.update((byte) b);
                        fosFinal.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        macFinal.update(b, off, len);
                        fosFinal.write(b, off, len);
                    }

                    @Override
                    public void close() throws IOException {
                        fosFinal.flush();
                    }
                };

                try (CipherOutputStream cos = new CipherOutputStream(hmacOutputStream, cipher)) {
                    byte[] buffer = new byte[4096];
                    long totalBytes = inputFile.length();
                    long processed = 0;
                    int read;

                    while ((read = fis.read(buffer)) != -1) {
                        cos.write(buffer, 0, read);
                        processed += read;
                        publishProgress((int) ((processed * 100) / totalBytes));
                    }
                }

                byte[] hmacFinalBytes = macFinal.doFinal();
                fos.write(hmacFinalBytes);
                fos.flush();

                File outFile = new File(outputPath);
                outFile.setReadable(false, false);
                outFile.setWritable(false, false);
                outFile.setReadable(true, true);
                outFile.setWritable(true, true);
            }

            // Limpar as chaves derivadas após uso
            if (aesKeyBytes != null) Arrays.fill(aesKeyBytes, (byte) 0);
            if (hmacKeyBytes != null) Arrays.fill(hmacKeyBytes, (byte) 0);

            ent = "CRIPTOGRAFADO!";
        } catch (Exception e) {
            ent = "Erro ao criptografar!";
        } finally {
            // Limpeza redundante para garantir não deixar dados na memória
            if (salt != null) Arrays.fill(salt, (byte) 0);
            if (ivBytes != null) Arrays.fill(ivBytes, (byte) 0);
            if (aesKeyBytes != null) Arrays.fill(aesKeyBytes, (byte) 0);
            if (hmacKeyBytes != null) Arrays.fill(hmacKeyBytes, (byte) 0);
            if (fullKey != null) Arrays.fill(fullKey, (byte) 0);
            if (passChars != null) Arrays.fill(passChars, '\0');
        }
        return null;
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        PG1.setProgress(values[0]);
        TX1.setText(values[0] + "%");
    }

    @Override
    protected void onPostExecute(Void aVoid) {
        PG1.setProgress(100);
        TX1.setText("100%");
        cd.dismiss();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Limpar campo senha e Editable na UI thread
        pass.getText().clear();
        pass.setText("");
    

        SketchwareUtil.showMessage(getApplicationContext(), ent);
    }
}.execute();



//Decrypt

class ArquivoAlteradoException extends Exception {
    public ArquivoAlteradoException(String mensagem) { super(mensagem); }
}

class ArquivoCorrompidoException extends Exception {
    public ArquivoCorrompidoException(String mensagem) { super(mensagem); }
}

class LimitedInputStream extends FilterInputStream {
    private long remaining;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) return -1;
        int result = super.read();
        if (result != -1) remaining--;
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0) return -1;
        len = (int)Math.min(len, remaining);
        int result = super.read(b, off, len);
        if (result != -1) remaining -= result;
        return result;
    }
}

new AsyncTask<Void, Integer, Void>() {
    private File tempFile;

    @Override
    protected void onPreExecute() {
        cd.show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ent = "DESCRIPTOGRAFANDO...";
        PG1.setProgress(0);
        TX1.setText("0%");
    }

    @Override
    protected Void doInBackground(Void... voids) {
        FileInputStream fisHMAC = null;
        FileInputStream fisDecrypt = null;
        FileOutputStream fos = null;

        byte[] salt = null, ivBytes = null, aesKeyBytes = null, hmacKeyBytes = null;
        byte[] fullKey = null, hmacStored = null, hmacCalculated = null;
        char[] passChars = null;

        try {
            File inputFile = new File(pathIn);
            long totalBytes = inputFile.length();
            if (totalBytes < 64)
                throw new ArquivoCorrompidoException("Arquivo muito pequeno.");

            // 1. Ler SALT e IV para HMAC
            fisHMAC = new FileInputStream(inputFile);
            salt = new byte[16];
            ivBytes = new byte[16];
            if (fisHMAC.read(salt) != 16 || fisHMAC.read(ivBytes) != 16)
                throw new ArquivoCorrompidoException("Erro ao ler SALT ou IV");

            // 2. Extrair senha e derivar chaves
            Editable editable = pass.getText();

            // Copiar senha para char[]
            passChars = new char[editable.length()];
            editable.getChars(0, editable.length(), passChars, 0);

            // Limpar Editable rápido
            pass.getText().clear();

            // Gerar chave com PBKDF2
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(passChars, salt, 100000, 512);
            SecretKey tmp = factory.generateSecret(spec);
            fullKey = tmp.getEncoded();

            // Limpar passChars logo após derivar chave
            Arrays.fill(passChars, '\0');
            passChars = null;

            aesKeyBytes = Arrays.copyOfRange(fullKey, 0, 32);
            hmacKeyBytes = Arrays.copyOfRange(fullKey, 32, 64);

            // Limpar fullKey após copiar
            Arrays.fill(fullKey, (byte) 0);
            fullKey = null;

            SecretKeySpec aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            SecretKeySpec hmacKey = new SecretKeySpec(hmacKeyBytes, "HmacSHA256");

            long encryptedLength = totalBytes - 16 - 16 - 32;
            if (encryptedLength <= 0)
                throw new ArquivoCorrompidoException("Tamanho inválido.");

            // 3. Calcular HMAC para verificar integridade
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            mac.update(salt);
            mac.update(ivBytes);

            byte[] buffer = new byte[4096];
            long totalRead = 0;
            int read;
            while (totalRead < encryptedLength &&
                   (read = fisHMAC.read(buffer, 0, (int)Math.min(buffer.length, encryptedLength - totalRead))) != -1) {
                mac.update(buffer, 0, read);
                totalRead += read;
                publishProgress((int)((totalRead * 100) / encryptedLength));
            }

            hmacStored = new byte[32];
            if (fisHMAC.read(hmacStored) != 32)
                throw new ArquivoCorrompidoException("HMAC ausente ou incompleto.");

            hmacCalculated = mac.doFinal();
            if (!MessageDigest.isEqual(hmacStored, hmacCalculated))
                throw new ArquivoAlteradoException("Arquivo alterado ou senha incorreta.");

            // 4. Segundo FileInputStream para descriptografar
            fisDecrypt = new FileInputStream(inputFile);
            if (fisDecrypt.skip(32) != 32)  // SALT + IV
                throw new ArquivoCorrompidoException("Erro ao pular cabeçalho");

            tempFile = new File(pathOut + "." + UUID.randomUUID().toString() + ".tmp");
            fos = new FileOutputStream(tempFile);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(ivBytes));
            CipherInputStream cis = new CipherInputStream(new BufferedInputStream(new LimitedInputStream(fisDecrypt, encryptedLength)), cipher);

            long decryptedBytes = 0;
            while ((read = cis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                decryptedBytes += read;
                publishProgress((int)((decryptedBytes * 100) / encryptedLength));
            }

            cis.close();
            fos.flush();
            fos.close();
            fisHMAC.close();
            fisDecrypt.close();

            File finalFile = new File(pathOut);
            if (finalFile.exists()) finalFile.delete();
            if (!tempFile.renameTo(finalFile)) {
                ent = "Erro ao renomear arquivo final!";
            } else {
                ent = "DESCRIPTOGRAFADO!";
            }

            // Limpar as chaves derivadas após uso
            if (aesKeyBytes != null) Arrays.fill(aesKeyBytes, (byte) 0);
            if (hmacKeyBytes != null) Arrays.fill(hmacKeyBytes, (byte) 0);

        } catch (ArquivoAlteradoException e) {
            ent = "Senha errada ou arquivo alterado!";
            excluirSeguramenteTemp();
        } catch (ArquivoCorrompidoException e) {
            ent = "Arquivo corrompido!";
            excluirSeguramenteTemp();
        } catch (Exception e) {
            if (e instanceof BadPaddingException || (e.getCause() instanceof BadPaddingException)) {
                ent = "Senha incorreta!";
            } else {
                ent = "Erro na descriptografia!";
            }
            excluirSeguramenteTemp();
        } finally {
            if (salt != null) Arrays.fill(salt, (byte) 0);
            if (ivBytes != null) Arrays.fill(ivBytes, (byte) 0);
            if (aesKeyBytes != null) Arrays.fill(aesKeyBytes, (byte) 0);
            if (hmacKeyBytes != null) Arrays.fill(hmacKeyBytes, (byte) 0);
            if (fullKey != null) Arrays.fill(fullKey, (byte) 0);
            if (hmacStored != null) Arrays.fill(hmacStored, (byte) 0);
            if (hmacCalculated != null) Arrays.fill(hmacCalculated, (byte) 0);
            if (passChars != null) Arrays.fill(passChars, '\0');
            try {
                if (fos != null) fos.close();
                if (fisHMAC != null) fisHMAC.close();
                if (fisDecrypt != null) fisDecrypt.close();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void excluirSeguramenteTemp() {
        if (tempFile != null && tempFile.exists()) {
            try {
                RandomAccessFile raf = new RandomAccessFile(tempFile, "rw");
                byte[] zeros = new byte[4096];
                long length = raf.length();
                raf.seek(0);
                while (length > 0) {
                    int toWrite = (int) Math.min(zeros.length, length);
                    raf.write(zeros, 0, toWrite);
                    length -= toWrite;
                }
                raf.close();
            } catch (Exception ignored) {}
            tempFile.delete();
        }
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        PG1.setProgress(values[0]);
        TX1.setText(values[0] + "%");
    }

    @Override
    protected void onPostExecute(Void aVoid) {
        PG1.setProgress(100);
        TX1.setText("100%");
        cd.dismiss();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Limpar campo senha e Editable na UI thread
        pass.getText().clear();
        pass.setText("");

        SketchwareUtil.showMessage(getApplicationContext(), ent);
    }
}.execute();
