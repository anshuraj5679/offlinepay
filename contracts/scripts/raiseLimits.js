// Raise the vault caps so a receiver can lock everything they've ever
// been paid, and can sign single vouchers larger than the demo default.
// The vault owner is the deployer key; setLimits is onlyOwner.
const { ethers, network } = require("hardhat");
const fs = require("fs");
const path = require("path");

async function main() {
  const dep = JSON.parse(fs.readFileSync(
    path.resolve(__dirname, "..", "deployments", `${network.name}.json`), "utf8"));
  const [signer] = await ethers.getSigners();
  const vault = await ethers.getContractAt("OfflineVault", dep.vault, signer);

  const MAX_SINGLE   = 1_000_000_000_000n; // $1,000,000 (effectively no cap for a demo)
  const MAX_BALANCE  = 1_000_000_000_000n;
  const TTL_SECONDS  = 7 * 24 * 60 * 60;   // 7 days

  console.log("Vault:", dep.vault);
  console.log("Signer:", signer.address);
  console.log("Setting maxSingle =", MAX_SINGLE.toString(),
              "maxBalance =", MAX_BALANCE.toString(),
              "ttl =", TTL_SECONDS);
  const tx = await vault.setLimits(MAX_SINGLE, MAX_BALANCE, TTL_SECONDS);
  console.log("tx:", tx.hash);
  const rcpt = await tx.wait();
  console.log("mined in block", rcpt.blockNumber);
  console.log("new maxSinglePayment:", (await vault.maxSinglePayment()).toString());
  console.log("new maxLockedBalance:", (await vault.maxLockedBalance()).toString());
}
main().catch(e => { console.error(e); process.exit(1); });
