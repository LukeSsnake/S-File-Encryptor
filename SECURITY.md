## 📄 RELATÓRIO DE ANÁLISE DE SEGURANÇA DO CÓDIGO CRIPTOGRÁFICO

1. Algoritmos e Mecanismos Criptográficos

- Derivação de chave:
  PBKDF2 com HmacSHA256, utilizando 100.000 iterações e salt de 16 bytes.
  Adequado para derivação segura de chaves a partir da senha.

- Criptografia:
  AES-256 em modo CBC com PKCS5Padding e IV de 16 bytes gerado por SecureRandom.
  O uso de AES-CBC é seguro quando combinado com autenticação.
  O código adota o padrão Encrypt-then-MAC, considerado a abordagem correta.

- Integridade e autenticidade:
  HMAC-SHA256 calculado sobre salt + IV + ciphertext.
  Garante proteção contra alterações, corrupção de dados e falsificações.

2. Manuseio de Senha e Chaves

- A senha é lida da interface, copiada para um array char[], utilizada e limpa logo após o uso.
- A chave derivada é separada em duas partes: uma para AES e outra para HMAC.
- As chaves derivadas são zeradas da memória após o uso.
- Limpeza redundante realizada na seção finally, reduzindo o risco de vazamento de dados sensíveis.
- Observação: a limpeza do campo Editable na thread de background pode não ser ideal do ponto de vista de UI/threading.

3. Fluxo de Criptografia e Armazenamento

- Estrutura do arquivo criptografado:
  1) Salt
  2) IV
  3) Dados cifrados
  4) HMAC

- As permissões do arquivo são ajustadas para restringir acessos indevidos.
- Uso correto de streams encadeados para cifragem e atualização simultânea do HMAC durante a gravação.

4. Fluxo de Descriptografia e Verificação

- Leitura inicial do salt e IV para derivação das chaves.
- Cálculo e verificação do HMAC antes da descriptografia, garantindo a integridade do arquivo.
- Uso de LimitedInputStream para limitar a leitura ao tamanho correto do ciphertext.
- Descriptografia realizada com CipherInputStream.
- Escrita inicial em arquivo temporário.
- Em caso de erro, o arquivo temporário é sobrescrito com zeros e removido de forma segura.
- O arquivo final somente substitui o original após verificação completa de integridade.

5. Tratamento de Exceções e Limpeza

- Exceções específicas são tratadas com mensagens claras e apropriadas.
- Variáveis sensíveis são limpas sistematicamente.
- Streams e recursos são corretamente fechados na seção finally.

# Política de Segurança

## 📌 Visão geral

Este projeto disponibiliza **trechos de código** relacionados à criptografia de arquivos no Android. Ele **não é um produto final**, nem um aplicativo pronto para uso em produção. O código é fornecido **apenas para fins educacionais e de estudo**.

## 🔐 Tecnologias de segurança utilizadas

Dependendo do trecho analisado, este projeto pode utilizar:

* Criptografia AES (Advanced Encryption Standard)
* Derivação de chave via PBKDF2
* HMAC para verificação de integridade
* Ofuscação de código com **ProGuard**
* Ofuscação de strings com **StringFog**

## ⚠️ Aviso importante sobre antivírus

> **PS:** As tecnologias de encriptação e ofuscação de código, como **StringFog** e **ProGuard**, podem ocasionalmente disparar **falsos-positivos em antivírus no Android**.

Isso ocorre porque antivírus heurísticos podem interpretar técnicas de ofuscação e criptografia como comportamento suspeito. **Isso não significa que o código seja malicioso**.

## 🧪 Status de segurança

* ❌ O código **não foi auditado formalmente** por terceiros
* ❌ Não há garantia de resistência contra ataques avançados
* ✅ Adequado para aprendizado, testes e projetos pessoais

## 🚫 Uso em produção

**Não é recomendado** utilizar este código diretamente em aplicações críticas ou comerciais sem:

* Auditoria de segurança independente
* Testes extensivos
* Adequações às boas práticas atuais de criptografia

## 🐞 Reportando vulnerabilidades

Se você identificar alguma falha de segurança ou comportamento inesperado:

* Abra uma **Issue** no GitHub descrevendo o problema
* Não divulgue publicamente falhas críticas sem aviso prévio

## 📄 Licença e responsabilidade

O autor **não se responsabiliza** por danos, perda de dados ou problemas de segurança decorrentes do uso deste código.

O uso é de inteira responsabilidade do desenvolvedor que o integrar em seu projeto.
