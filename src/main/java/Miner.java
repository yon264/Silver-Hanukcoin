import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;


public class Miner extends Thread{
    private int maxThreads;
    private ConcurrentLinkedQueue<Block> solutions = new ConcurrentLinkedQueue<>();
    private ArrayList<SolverThread> threads = new ArrayList<>();
    private Block lastBlock;
    private Server server;
    public AtomicBoolean blockchainChanged;
    public AtomicBoolean lastBlockIsOurs;
    private int wallet;
    private final String ALT_NAME = "Silver2"; //TODO replace
    private String currName;


    public Miner(int maxThreads, Server server){
        this.server = server;
        this.lastBlock = Database.getLatestBlock(); //get the latest block
        this.maxThreads = maxThreads;
        //checking if last block is ours
        lastBlockIsOurs = new AtomicBoolean(isOurs(lastBlock));
        //done!

        this.blockchainChanged = new AtomicBoolean(false);
        for (int i = 0; i < maxThreads; ++i) {
            SolverThread st = new SolverThread(i); // make sure serial number seeds are ok.
            threads.add(st);
        }
    }

    public void updateBlock(Block newBlock){
        if (newBlock.getSerial_number() > lastBlock.getSerial_number()){
            lastBlock = newBlock;
            blockchainChanged.set(true);
            lastBlockIsOurs.set(isOurs(newBlock));
        }
    }

    private boolean isOurs(Block lastBlock){
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            System.out.format("[!] ERROR no such algorithm MD5\nDetails:\n%s", e.toString());
            return false;
        }
        byte[] nameSig = Arrays.copyOfRange(md5.digest(Main.NAME.getBytes()), 0, 4);
        wallet = Utils.bytesToInt(nameSig);
        return wallet == lastBlock.getWallet();
    }

    private int genWallet(String name){
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            System.out.format("[!] ERROR no such algorithm MD5\nDetails:\n%s", e.toString());
            return 0; //shouldnt get here..
        }

        byte[] nameSig = Arrays.copyOfRange(md5.digest(name.getBytes()), 0, 4);
        return Utils.bytesToInt(nameSig);
    }

    private void updateWallet(){
        if (!lastBlockIsOurs.get()){
            wallet = genWallet(Main.NAME);
            currName = Main.NAME;
            System.out.println("[*] current wallet is " + Main.NAME);
        } else {
            wallet = genWallet(ALT_NAME);
            currName = ALT_NAME;
            System.out.println("[*] current wallet is " + ALT_NAME);
        }
    }
    @Override
    public void run() {

        updateWallet();

        System.out.println("[*] initialized miner and started mining on new block: " + lastBlock.toString());
        for (SolverThread st : threads){
            st.start();
        }


        while (true) {
            //check if anyone has solved the riddle.
            if (!solutions.isEmpty()) {
                Block candidate = solutions.remove();
                server.parseSolvedPuzzle(candidate);
            }

            //check if blockchain changed
            if (blockchainChanged.compareAndSet(true, false)) {
                System.out.println("[*] Started mining a new block: " + lastBlock.toString());
                lastBlock = Database.getLatestBlock();
                lastBlockIsOurs.set(isOurs(lastBlock));
                updateWallet();
            }

        }
    }

    private class SolverThread extends Thread{
        private final long seed;

        public SolverThread(long seed){
            this.seed = seed;
        }

        @Override
        public void run() {
            Random generator = new Random(seed);

            while (true){
                // generate new goals
                System.out.println("[*] Refreshed miner " + this.toString() + " and started mining #" + lastBlock.getSerial_number());

                int serial = lastBlock.getSerial_number() + 1;
                byte[] prevSig = Arrays.copyOfRange(lastBlock.getSig(), 0, 8);
                byte[] puzzle = new byte[8];
                Block b = new Block(serial, wallet, prevSig, puzzle, new byte[12]);
                // start mining
                Outer:
                //in every iteration, check if there is a change in the blockchain
                while (!blockchainChanged.get()) {
                    generator.nextBytes(puzzle); //is mutable so b.puzzle changes
                    //update b
                    b.setSerial_number(serial);
                    b.setWallet(wallet);
                    b.setPrev_sig(prevSig);


                    byte[] hash;
                    try {
                        hash = b.calcMD5();
                    } catch (NoSuchAlgorithmException e) {
                        System.out.format("[!] ERROR no such algorithm MD5\nDetails:\n%s", e.toString());
                        return;
                    }
                    //check if puzzle is solved
                    int index = 15; //iterating from end to start
                    int numZerosToCheck = b.calcNZ();
                    while (numZerosToCheck >= 8) {
                        if (hash[index] != 0) {
                            continue Outer;
                        }
                        --index;
                        numZerosToCheck -= 8;
                    }
                    //there are less than 8 bits to check
                    if (numZerosToCheck > 0) { //there are bits left
                        if ((hash[index] & ((1 << numZerosToCheck) - 1)) != 0) { //mask
                            continue;
                        }
                    }
                    b.setSig(Arrays.copyOfRange(hash, 0, 12));
                    solutions.add(b);
                    break;
                }

                // if we got here, it means the blockchain changed. if its because we mined, wait until another block arrives
                System.out.println("[*] blockchain changed...");

            }
        }
    }
}
