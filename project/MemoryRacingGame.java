import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import java.util.*;
import java.util.List;

/**
 * 雙人記憶競速挑戰 - 音樂功能強化版
 * 1. 音樂控制：右上角新增音效按鈕，點擊可切換音樂開/關。
 * 2. 音效引擎：新增 SoundManager 類別處理循環背景音樂 (預設讀取 music/bgm.wav)。
 * 3. 修正重疊問題：優化 drawQuiz 座標，拉開作答者頭像與標題文字的垂直距離。
 * 4. 視覺動態：結算畫面頭像搖擺、開始畫面雙人同步跳動、彩帶垂直均勻散落。
 */
public class MemoryRacingGame extends JFrame {
    public static final int WIDTH = 1000;
    public static final int HEIGHT = 700;
    private GamePanel gamePanel;

    public MemoryRacingGame() {
        setTitle("記憶競速大對決");
        setSize(WIDTH, HEIGHT);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setLocationRelativeTo(null);

        gamePanel = new GamePanel();
        add(gamePanel);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                gamePanel.getEngine().handleInput(e.getKeyCode());
            }
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MemoryRacingGame().setVisible(true));
    }
}

// ==========================================
// 1. 音效管理系統 (SoundManager)
// ==========================================
class SoundManager {
    private Clip bgmClip;
    private boolean isMuted = false;
    private final String BGM_PATH = "music/bgm.wav";

    public SoundManager() {
        loadBGM();
    }

    private void loadBGM() {
        try {
            File audioFile = new File(BGM_PATH);
            if (audioFile.exists()) {
                AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile);
                bgmClip = AudioSystem.getClip();
                bgmClip.open(audioStream);
                bgmClip.loop(Clip.LOOP_CONTINUOUSLY); // 設定循環播放
                bgmClip.start();
            } else {
                System.out.println("找不到背景音樂檔案: " + BGM_PATH + " (請放置 .wav 檔案)");
            }
        } catch (Exception e) {
            System.out.println("音訊初始化失敗: " + e.getMessage());
        }
    }

    public void toggleMute() {
        isMuted = !isMuted;
        if (bgmClip != null) {
            if (isMuted) {
                bgmClip.stop();
            } else {
                bgmClip.loop(Clip.LOOP_CONTINUOUSLY);
                bgmClip.start();
            }
        }
    }

    public boolean isMuted() {
        return isMuted;
    }
}

// ==========================================
// 2. 遊戲邏輯引擎 (GameEngine)
// ==========================================
class GameEngine {
    enum State { START, INSTRUCTIONS, COUNTDOWN, RACING, QUIZ, ROUND_END, GAME_OVER }
    enum Difficulty { EASY, NORMAL, HARD }

    public State currentState = State.START;
    public Difficulty difficulty = Difficulty.EASY;
    public int currentRound = 1;

    public String p1Name = "玉米濃ㄊㄥ", p2Name = "菜包";
    public Image p1Icon, p2Icon;
    public Image[] taskImages = new Image[8];
    public Image[] distractorImages = new Image[4];

    private final String P1_IMG_PATH = "image/p1.png";
    private final String P2_IMG_PATH = "image/p2.png";
    private final String[] TASK_IMG_PATHS = {
        "image/t1.jpg", "image/t2.jpg", "image/t3.jpg", "image/t4.jpg",
        "image/t5.jpg", "image/t6.jpg", "image/t7.jpg", "image/t8.jpg"
    };
    private final String[] DIST_IMG_PATHS = {
        "image/d1.png", "image/d2.jpg", "image/d3.jpg", "image/d4.jpg"
    };

    public float p1Pos = 50, p2Pos = 50;
    public int p1Score = 0, p2Score = 0;
    private int p1LastKey = -1, p2LastKey = -1;
    public static final float FINISH_LINE = 850;

    public List<SkyObject> skyObjects = new ArrayList<>();
    public List<String> roundImages = new ArrayList<>();
    public Map<String, Integer> imageCounts = new HashMap<>();
    public int winnerOfRace = 0, currentQuizPlayer = 0, quizAttempts = 0;

    public int countdownValue = 3;
    private long countdownStart;
    public float demoRunnerYOffset = 0;
    public float animTick = 0;
    public List<Confetti> confettiList = new ArrayList<>();

    private List<Integer> hardSpawningQueue = new ArrayList<>();

    private QuizSystem quizSystem = new QuizSystem();
    public SoundManager soundManager = new SoundManager(); // 初始化音效管理
    private Random random = new Random();
    private JPanel parent;

    public GameEngine(JPanel parent) {
        this.parent = parent;
        loadIcons();
    }

    private void loadIcons() {
        new Thread(() -> {
            try {
                File f1 = new File(P1_IMG_PATH); if (f1.exists()) p1Icon = ImageIO.read(f1);
                File f2 = new File(P2_IMG_PATH); if (f2.exists()) p2Icon = ImageIO.read(f2);
                for (int i = 0; i < 8; i++) {
                    File ft = new File(TASK_IMG_PATHS[i]); if (ft.exists()) taskImages[i] = ImageIO.read(ft);
                }
                for (int i = 0; i < 4; i++) {
                    File fd = new File(DIST_IMG_PATHS[i]); if (fd.exists()) distractorImages[i] = ImageIO.read(fd);
                }
            } catch (Exception e) { System.out.println("本地圖片載入失敗，請檢查 image 資料夾。"); }
        }).start();
    }

    public void startCountdown() {
        countdownValue = 3; countdownStart = System.currentTimeMillis(); currentState = State.COUNTDOWN;
    }

    public void initRound() {
        p1Pos = 50; p2Pos = 50; p1LastKey = -1; p2LastKey = -1;
        skyObjects.clear(); roundImages.clear(); imageCounts.clear(); winnerOfRace = 0;

        if (difficulty == Difficulty.HARD) {
            hardSpawningQueue.clear();
            for (int i = 0; i < 8; i++) hardSpawningQueue.add(i);
            Collections.shuffle(hardSpawningQueue);
        }

        currentState = State.RACING;
    }

    public void handleInput(int keyCode) {
        if (currentState == State.START) { if (keyCode == KeyEvent.VK_SPACE) startCountdown(); }
        else if (currentState == State.RACING) updateRacingInput(keyCode);
        else if (currentState == State.QUIZ) updateQuizInput(keyCode);
        else if (currentState == State.ROUND_END) startCountdown();
    }

    private void updateRacingInput(int keyCode) {
        if ((keyCode == KeyEvent.VK_LEFT || keyCode == KeyEvent.VK_RIGHT) && keyCode != p1LastKey) {
            p1Pos += 8; p1LastKey = keyCode;
        }
        if ((keyCode == KeyEvent.VK_A || keyCode == KeyEvent.VK_D) && keyCode != p2LastKey) {
            p2Pos += 8; p2LastKey = keyCode;
        }
        if (p1Pos >= FINISH_LINE) { winnerOfRace = 1; currentQuizPlayer = 1; startQuiz(); }
        else if (p2Pos >= FINISH_LINE) { winnerOfRace = 2; currentQuizPlayer = 2; startQuiz(); }
    }

    private void startQuiz() {
        currentState = State.QUIZ; quizAttempts = 0; skyObjects.clear();
        quizSystem.generateQuestion(difficulty, roundImages, imageCounts);
    }

    private void updateQuizInput(int keyCode) {
        int idx = -1;
        if (keyCode == KeyEvent.VK_1 || keyCode == KeyEvent.VK_NUMPAD1) idx = 0;
        else if (keyCode == KeyEvent.VK_2 || keyCode == KeyEvent.VK_NUMPAD2) idx = 1;
        else if (keyCode == KeyEvent.VK_3 || keyCode == KeyEvent.VK_NUMPAD3) idx = 2;

        if (idx != -1) {
            QuizSystem.Type type = quizSystem.getCurrentType();
            int actualIdx = (difficulty == Difficulty.HARD && type == QuizSystem.Type.COUNT) ? idx + 1 : idx;

            if (actualIdx < quizSystem.getOptions().size()) {
                boolean correct = quizSystem.processInput(actualIdx);
                if (correct) { updateScore(currentQuizPlayer, 25); showResult(true, ""); }
                else {
                    if (quizAttempts == 0) {
                        quizAttempts++; currentQuizPlayer = (currentQuizPlayer == 1) ? 2 : 1;
                        JOptionPane.showMessageDialog(parent, "答錯了！換另一位玩家作答");
                    } else showResult(false, quizSystem.getFeedback());
                }
            }
        }
    }

    private void updateScore(int player, int points) {
        if (player == 1) p1Score += points; else p2Score += points;
    }

    private void showResult(boolean correct, String feedback) {
        String msg = correct ? "回答正確！加 25 分" : "可惜答錯了...\n" + feedback;
        JOptionPane.showMessageDialog(parent, msg);
        if (currentRound < 3) {
            currentRound++;
            difficulty = (difficulty == Difficulty.EASY) ? Difficulty.NORMAL : Difficulty.HARD;
            currentState = State.ROUND_END;
        } else { currentState = State.GAME_OVER; spawnConfetti(); }
    }

    private void spawnConfetti() {
        confettiList.clear();
        Color[] colors = {Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, Color.MAGENTA, Color.WHITE};
        for (int i = 0; i < 150; i++) {
            float vx = (random.nextFloat() - 0.5f) * 6;
            float vy = 2 + random.nextFloat() * 5;
            confettiList.add(new Confetti(random.nextInt(MemoryRacingGame.WIDTH), -random.nextInt(MemoryRacingGame.HEIGHT), vx, vy, colors[random.nextInt(colors.length)]));
        }
    }

    public void updateWorld() {
        animTick += 0.15f;
        demoRunnerYOffset = (float) Math.abs(Math.sin(animTick)) * 12;
        if (currentState == State.GAME_OVER) for (Confetti c : confettiList) c.update();
        if (currentState == State.START || currentState == State.RACING || currentState == State.COUNTDOWN) {
            boolean isRacing = (currentState == State.RACING);
            int minGap = (difficulty == Difficulty.HARD) ? 120 : 260;
            boolean spaceAvailable = skyObjects.isEmpty() || (1050 - skyObjects.get(skyObjects.size()-1).x > minGap);

            if (spaceAvailable && random.nextInt(100) < 5) {
                if (isRacing) {
                    if (difficulty == Difficulty.HARD) {
                        int photoIdx;
                        if (!hardSpawningQueue.isEmpty()) photoIdx = hardSpawningQueue.remove(0);
                        else photoIdx = random.nextInt(8);
                        String imgTag = "TASK_" + photoIdx;
                        skyObjects.add(new SkyObject(1050, 80 + random.nextInt(200), 8.0f, imgTag));
                        roundImages.add(imgTag);
                        imageCounts.put(imgTag, imageCounts.getOrDefault(imgTag, 0) + 1);
                    } else {
                        String[] pool = quizSystem.getPool(difficulty);
                        String img = pool[random.nextInt(pool.length)];
                        float speed = (difficulty == Difficulty.EASY) ? 3.5f : 5.5f;
                        skyObjects.add(new SkyObject(1050, 80 + random.nextInt(200), speed, img));
                        roundImages.add(img);
                        imageCounts.put(img, imageCounts.getOrDefault(img, 0) + 1);
                    }
                } else skyObjects.add(new SkyObject(1050, 50 + random.nextInt(150), 1.5f, "WHITE_CLOUD"));
            }
            for (int i = skyObjects.size() - 1; i >= 0; i--) {
                skyObjects.get(i).x -= skyObjects.get(i).speed;
                if (skyObjects.get(i).x < -100) skyObjects.remove(i);
            }
        }
        if (currentState == State.COUNTDOWN) {
            long elapsed = System.currentTimeMillis() - countdownStart;
            countdownValue = 3 - (int)(elapsed / 1000);
            if (countdownValue <= 0) initRound();
        }
    }
    public QuizSystem getQuizSystem() { return quizSystem; }
}

// ==========================================
// 3. 視覺特效
// ==========================================
class Confetti {
    float x, y, vx, vy, angle = 0, rotSpeed; int size; Color color;
    public Confetti(float x, float y, float vx, float vy, Color color) {
        this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.color = color;
        this.rotSpeed = (new Random().nextFloat() - 0.5f) * 0.2f; this.size = 8 + new Random().nextInt(8);
    }
    public void update() {
        x += vx; y += vy; angle += rotSpeed;
        vx *= 0.985;
        vx += (new Random().nextFloat() - 0.5f) * 0.15f;

        if (y > 700) {
            y = -20;
            x = new Random().nextInt(MemoryRacingGame.WIDTH);
            vx = (new Random().nextFloat() - 0.5f) * 4;
        }
    }
    public void draw(Graphics2D g2) {
        Graphics2D gTemp = (Graphics2D) g2.create();
        gTemp.setColor(color); gTemp.translate(x, y); gTemp.rotate(angle);
        gTemp.fillRect(-size/2, -size/4, size, size/2); gTemp.dispose();
    }
}

// ==========================================
// 4. 問答系統 (QuizSystem)
// ==========================================
class QuizSystem {
    enum Type { IDENTIFY, COUNT }

    private final String[] EASY_POOL = {"🍎", "🍌", "🍒", "🍍", "🍓", "🍑", "🍉"};
    private final String[] NORMAL_POOL = {"🐶", "🐱", "🐭", "🦄", "🐰", "🐷", "🐻", "🐼"};

    private final String[] EASY_DIST_POOL = {"🍊", "🍐", "🍇", "🍆", "🥕", "🌽"};
    private final String[] NORMAL_DIST_POOL = {"🦊", "🐯", "🐵", "🦁", "🐸", "🐔"};

    private Type currentType;
    private String questionText, feedback;
    private List<String> options = new ArrayList<>();
    private String correctValue;

    public void generateQuestion(GameEngine.Difficulty diff, List<String> seen, Map<String, Integer> counts) {
        Random rand = new Random();
        options.clear();

        if (diff == GameEngine.Difficulty.HARD) {
            currentType = rand.nextBoolean() ? Type.IDENTIFY : Type.COUNT;
            if (currentType == Type.IDENTIFY) {
                String target = seen.isEmpty() ? "TASK_0" : seen.get(rand.nextInt(seen.size()));
                questionText = "Q:剛才天空中出現過哪一張照片？";
                correctValue = target;
                options.add(target);
                Set<Integer> dIndices = new HashSet<>();
                while(dIndices.size() < 2) dIndices.add(rand.nextInt(4));
                for(int idx : dIndices) options.add("DIST_" + idx);
                Collections.shuffle(options);
                feedback = "正確答案是選項 " + (options.indexOf(correctValue) + 1);
            } else {
                List<String> keys = new ArrayList<>(counts.keySet());
                String countTarget = keys.isEmpty() ? "TASK_0" : keys.get(rand.nextInt(keys.size()));
                int correctCount = counts.getOrDefault(countTarget, 1);
                questionText = "Q:請問這張照片剛才出現過幾次？";
                correctValue = String.valueOf(correctCount);
                Set<String> uniqueNums = new LinkedHashSet<>();
                uniqueNums.add(correctValue);
                while(uniqueNums.size() < 3) {
                    uniqueNums.add(String.valueOf(Math.max(1, correctCount + (rand.nextInt(5) - 2))));
                }
                List<String> numList = new ArrayList<>(uniqueNums);
                Collections.shuffle(numList);
                options.add(countTarget);
                options.addAll(numList);
                feedback = "正確答案是選項 " + (options.indexOf(correctValue));
            }
        } else {
            currentType = (diff == GameEngine.Difficulty.EASY) ? Type.IDENTIFY : (rand.nextBoolean() ? Type.IDENTIFY : Type.COUNT);
            if (seen.isEmpty()) seen.add(getPool(diff)[0]);

            String[] currentDistPool = (diff == GameEngine.Difficulty.EASY) ? EASY_DIST_POOL : NORMAL_DIST_POOL;

            if (currentType == Type.IDENTIFY) {
                Set<String> unique = new HashSet<>(seen);
                String target = new ArrayList<>(unique).get(rand.nextInt(unique.size()));
                questionText = "Q:剛才出現過哪一張圖片？";
                correctValue = target;
                options.add(target);

                List<String> dists = new ArrayList<>(Arrays.asList(currentDistPool));
                dists.removeAll(unique);
                Collections.shuffle(dists);
                for (int i = 0; i < 2 && i < dists.size(); i++) options.add(dists.get(i));

                Collections.shuffle(options);
                feedback = "正確答案是選項 " + (options.indexOf(correctValue) + 1);
            } else {
                List<String> keys = new ArrayList<>(counts.keySet());
                String target = keys.get(rand.nextInt(keys.size()));
                int correctCount = counts.get(target);
                questionText = "請問圖片 " + target + " 出現過幾次？";
                correctValue = String.valueOf(correctCount);

                Set<String> uniqueNums = new HashSet<>();
                uniqueNums.add(correctValue);
                while(uniqueNums.size() < 3) uniqueNums.add(String.valueOf(Math.max(1, correctCount + (rand.nextInt(5) - 2))));
                List<String> numList = new ArrayList<>(uniqueNums);
                Collections.shuffle(numList);
                options.addAll(numList);
                feedback = "正確答案是選項 " + (options.indexOf(correctValue) + 1);
            }
        }
    }
    public boolean processInput(int idx) { return options.get(idx).equals(correctValue); }
    public String[] getPool(GameEngine.Difficulty diff) { return diff == GameEngine.Difficulty.EASY ? EASY_POOL : NORMAL_POOL; }
    public String getQuestionText() { return questionText; }
    public List<String> getOptions() { return options; }
    public String getFeedback() { return feedback; }
    public Type getCurrentType() { return currentType; }
}

// ==========================================
// 5. 繪圖面板 (GamePanel)
// ==========================================
class GamePanel extends JPanel implements ActionListener {
    private GameEngine engine;
    private Rectangle startBtn = new Rectangle(0, 400, 200, 55), infoBtn = new Rectangle(0, 475, 200, 55), backBtn = new Rectangle(30, 30, 65, 45); 
    private Rectangle p1NameBtn = new Rectangle(0, 0, 65, 35), p2NameBtn = new Rectangle(0, 0, 65, 35);
    private Rectangle musicBtn = new Rectangle(930, 15, 45, 45); // 音效按鍵 Hitbox
    private final String FONT_NAME = "微軟正黑體", EMOJI_FONT = "SansSerif";
    private final Color BLUE_BTN_COLOR = new Color(52, 152, 219);

    public GamePanel() {
        setBackground(new Color(135, 206, 235)); this.engine = new GameEngine(this);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Point p = e.getPoint();
                // 音效按鈕點擊偵測（全域可用）
                if (musicBtn.contains(p)) {
                    engine.soundManager.toggleMute();
                    return;
                }

                if (engine.currentState == GameEngine.State.START) {
                    if (startBtn.contains(p)) engine.startCountdown();
                    else if (infoBtn.contains(p)) engine.currentState = GameEngine.State.INSTRUCTIONS;
                    else if (p1NameBtn.contains(p)) renamePlayer(1);
                    else if (p2NameBtn.contains(p)) renamePlayer(2);
                } else if (engine.currentState == GameEngine.State.INSTRUCTIONS && backBtn.contains(p)) engine.currentState = GameEngine.State.START;
                else if (engine.currentState == GameEngine.State.ROUND_END) engine.startCountdown();
                else if (engine.currentState == GameEngine.State.GAME_OVER) System.exit(0);
            }
        });
        new javax.swing.Timer(16, this).start();
    }

    public GameEngine getEngine() { return engine; }

    private void renamePlayer(int pNum) {
        String oldName = (pNum == 1) ? engine.p1Name : engine.p2Name;
        Image iconToUse = (pNum == 1) ? engine.p1Icon : engine.p2Icon;
        Icon swingIcon = null;

        if (iconToUse != null) {
            int iw = iconToUse.getWidth(null);
            int ih = iconToUse.getHeight(null);
            if (iw > 0 && ih > 0) {
                double scale = 80.0 / Math.max(iw, ih);
                int nw = (int) (iw * scale);
                int nh = (int) (ih * scale);
                swingIcon = new ImageIcon(iconToUse.getScaledInstance(nw, nh, Image.SCALE_SMOOTH));
            }
        }

        String n = (String) JOptionPane.showInputDialog(this, "輸入新名稱：", "修改名稱", JOptionPane.QUESTION_MESSAGE, swingIcon, null, oldName);
        if (n != null && !n.trim().isEmpty()) { if (pNum == 1) engine.p1Name = n.trim(); else engine.p2Name = n.trim(); }
    }

    @Override
    public void actionPerformed(ActionEvent e) { engine.updateWorld(); repaint(); }

    private void drawCharacter(Graphics2D g2, Image icon, int x, int y, int size, boolean vCenter) {
        if (icon != null) {
            int iw = icon.getWidth(null);
            int ih = icon.getHeight(null);
            if (iw > 0 && ih > 0) {
                double scale = (double) size / Math.max(iw, ih);
                int nw = (int) (iw * scale);
                int nh = (int) (ih * scale);
                int drawX = x - (nw / 2);
                int drawY = vCenter ? y - (nh / 2) : y - nh;
                g2.drawImage(icon, drawX, drawY, nw, nh, null);
            }
        } else {
            g2.setColor(Color.WHITE); g2.fillRect(x - size/2, y - size, size, size);
            g2.setColor(Color.DARK_GRAY); g2.setFont(new Font(FONT_NAME, Font.BOLD, size/3));
            g2.drawString("?", x - size/6, y - size/3);
        }
    }

    private void drawKeyIcon(Graphics2D g2, String keyText, int x, int y) {
        int w = 45, h = 45;
        g2.setColor(new Color(240, 240, 240)); g2.fillRoundRect(x, y, w, h, 10, 10);
        g2.setColor(Color.DARK_GRAY); g2.setStroke(new BasicStroke(2)); g2.drawRoundRect(x, y, w, h, 10, 10);
        g2.setFont(new Font(FONT_NAME, Font.BOLD, 18));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(keyText, x + (w - fm.stringWidth(keyText)) / 2, y + (h - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void drawBackground(Graphics2D g2) {
        g2.setColor(new Color(34, 139, 34)); g2.fillRect(0, 400, getWidth(), 300);
        g2.setColor(new Color(70, 70, 70)); g2.fillRect(0, 460, getWidth(), 60); g2.fillRect(0, 560, getWidth(), 60);
        for (SkyObject obj : engine.skyObjects) {
            if (obj.content.equals("WHITE_CLOUD")) {
                g2.setColor(new Color(255, 255, 255, 240));
                g2.fillOval((int)obj.x, (int)obj.y, 45, 28); g2.fillOval((int)obj.x + 18, (int)obj.y - 14, 55, 38); g2.fillOval((int)obj.x + 45, (int)obj.y, 45, 28);
            } else if (obj.content.startsWith("TASK_")) {
                int tidx = Integer.parseInt(obj.content.substring(5));
                if (engine.taskImages[tidx] != null) {
                    drawCharacter(g2, engine.taskImages[tidx], (int)obj.x + 40, (int)obj.y, 80, true);
                }
            } else {
                g2.setFont(new Font(EMOJI_FONT, Font.PLAIN, 50)); g2.drawString(obj.content, obj.x, obj.y);
            }
        }
    }

    private void drawMusicButton(Graphics2D g2) {
        boolean muted = engine.soundManager.isMuted();
        g2.setColor(new Color(0, 0, 0, 80));
        g2.fillOval(musicBtn.x, musicBtn.y, musicBtn.width, musicBtn.height);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(EMOJI_FONT, Font.PLAIN, 24));
        String icon = muted ? "🔇" : "🔊";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(icon, musicBtn.x + (musicBtn.width - fm.stringWidth(icon)) / 2, musicBtn.y + 32);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g); Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        switch (engine.currentState) {
            case START: drawStartScreen(g2); break;
            case INSTRUCTIONS: drawInstructions(g2); break;
            case COUNTDOWN: drawCountdown(g2); break;
            case RACING: drawGame(g2); break;
            case QUIZ: drawGame(g2); drawQuiz(g2); break;
            case ROUND_END: drawRoundEnd(g2); break;
            case GAME_OVER: drawGameOver(g2); break;
        }
        drawMusicButton(g2); // 永遠繪製音效按鈕
    }

    private void drawStartScreen(Graphics2D g2) {
        drawBackground(g2);
        int jumpY = 485 - (int)engine.demoRunnerYOffset;
        drawCharacter(g2, engine.p1Icon, 90, jumpY, 100, false);
        drawCharacter(g2, engine.p2Icon, 180, jumpY, 100, false);
        int boxX = (getWidth() - 480) / 2; g2.setColor(new Color(255, 255, 255, 160)); g2.fillRoundRect(boxX, 100, 480, 480, 30, 30);
        drawCentered(g2, "記憶競速大對決", 180, 50, Color.BLACK);
        int lX = boxX + 110;
        g2.setFont(new Font(FONT_NAME, Font.BOLD, 22)); g2.setColor(Color.DARK_GRAY);
        drawCharacter(g2, engine.p1Icon, lX, 300, 60, false);
        String p1Label = "玩家一： " + engine.p1Name;
        g2.drawString(p1Label, lX + 45, 285);
        p1NameBtn.x = lX + 45 + g2.getFontMetrics().stringWidth(p1Label) + 15;
        p1NameBtn.y = 260;
        g2.setColor(new Color(200, 200, 200));
        g2.fillRoundRect(p1NameBtn.x, p1NameBtn.y, p1NameBtn.width, p1NameBtn.height, 8, 8);
        g2.setColor(Color.BLACK);
        g2.setFont(new Font(FONT_NAME, Font.BOLD, 15));
        g2.drawString("更改", p1NameBtn.x + 15, p1NameBtn.y + 23);
        g2.setFont(new Font(FONT_NAME, Font.BOLD, 22)); g2.setColor(Color.DARK_GRAY);
        drawCharacter(g2, engine.p2Icon, lX, 380, 60, false);
        String p2Label = "玩家二： " + engine.p2Name;
        g2.drawString(p2Label, lX + 45, 365);
        p2NameBtn.x = lX + 45 + g2.getFontMetrics().stringWidth(p2Label) + 15;
        p2NameBtn.y = 340;
        g2.setColor(new Color(200, 200, 200));
        g2.fillRoundRect(p2NameBtn.x, p2NameBtn.y, p2NameBtn.width, p2NameBtn.height, 8, 8);
        g2.setColor(Color.BLACK);
        g2.setFont(new Font(FONT_NAME, Font.BOLD, 15));
        g2.drawString("更改", p2NameBtn.x + 15, p2NameBtn.y + 23);
        startBtn.x = (getWidth() - 200) / 2; infoBtn.x = (getWidth() - 200) / 2;
        g2.setColor(new Color(46, 204, 113)); g2.fillRoundRect(startBtn.x, startBtn.y, 200, 55, 20, 20);
        g2.setColor(Color.WHITE); drawTextInRect(g2, "開始遊戲", startBtn, 24);
        g2.setColor(BLUE_BTN_COLOR); g2.fillRoundRect(infoBtn.x, infoBtn.y, 200, 55, 20, 20);
        g2.setColor(Color.WHITE); drawTextInRect(g2, "遊戲說明", infoBtn, 24);
    }

    private void drawInstructions(Graphics2D g2) {
        g2.setColor(new Color(255, 255, 255, 220)); g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(BLUE_BTN_COLOR); g2.fillRoundRect(backBtn.x, backBtn.y, 65, 45, 15, 15);
        g2.setColor(Color.WHITE); g2.setStroke(new BasicStroke(3)); int cx = backBtn.x + 32, cy = backBtn.y + 22;
        g2.drawLine(cx-12, cy, cx+12, cy); g2.drawLine(cx-12, cy, cx-4, cy-8); g2.drawLine(cx-12, cy, cx-4, cy+8); 
        drawCentered(g2, "【 遊戲說明 】", 100, 36, Color.BLACK);
        String[] lines = {"1. 玩家一 (P1)：交替按 [ ← ][ → ] 前進。", "2. 玩家二 (P2)：交替按 [ A ][ D ] 前進。", "3. 觀察天空飛過的圖片，競速結束後會有作答環節。", "4. 若答錯對方可獲得答題機會！請務必仔細觀察。", "5.答對者可加25分，答錯不倒扣，共三回合。"};
        for (int i = 0; i < lines.length; i++) drawCentered(g2, lines[i], 180 + (i * 50), 22, Color.DARK_GRAY, true);
        int ky = 420; g2.setColor(new Color(0, 0, 0, 40)); g2.fillRoundRect(200, ky - 30, 600, 230, 20, 20);
        g2.setColor(Color.BLACK); g2.setFont(new Font(FONT_NAME, Font.BOLD, 20));
        g2.drawString("P1 操作：", 250, ky + 20); drawKeyIcon(g2, "←", 400, ky - 10); drawKeyIcon(g2, "→", 455, ky - 10);
        g2.drawString("P2 操作：", 250, ky + 80); drawKeyIcon(g2, "A", 400, ky + 50); drawKeyIcon(g2, "D", 455, ky + 50);
        g2.drawString("作答按鍵：", 250, ky + 140); drawKeyIcon(g2, "1", 400, ky + 110); drawKeyIcon(g2, "2", 455, ky + 110); drawKeyIcon(g2, "3", 510, ky + 110);
    }

    private void drawCountdown(Graphics2D g2) {
        drawGame(g2); g2.setColor(new Color(0, 0, 0, 100)); g2.fillRect(0, 0, getWidth(), getHeight());
        drawCentered(g2, String.valueOf(engine.countdownValue), 350, 120, Color.YELLOW);
    }

    private void drawGame(Graphics2D g2) {
        drawBackground(g2); g2.setColor(Color.WHITE); g2.fillRect(900, 460, 15, 160);
        drawCharacter(g2, engine.p1Icon, (int)engine.p1Pos, 510, 80, false); drawCharacter(g2, engine.p2Icon, (int)engine.p2Pos, 610, 80, false);
        g2.setColor(Color.BLACK); g2.setFont(new Font(FONT_NAME, Font.BOLD, 16));
        g2.drawString("回合: " + engine.currentRound + "/3 | 難度: " + engine.difficulty, 20, 30);
        g2.drawString(engine.p2Name + ": " + engine.p2Score + " | " + engine.p1Name + ": " + engine.p1Score, 750, 30);
    }

    private void drawQuiz(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 220)); g2.fillRect(0, 0, getWidth(), getHeight());
        String name = (engine.currentQuizPlayer == 1) ? engine.p1Name : engine.p2Name;
        drawCharacter(g2, (engine.currentQuizPlayer == 1 ? engine.p1Icon : engine.p2Icon), 500, 100, 100, true);
        drawCentered(g2, "【 當前作答者：" + name + " 】", 200, 32, Color.YELLOW);
        String hint = (engine.quizAttempts == 0) ? "請根據你的記憶作答" : "你答錯了！換另一位玩家作答";
        drawCentered(hint, 235, 18, Color.LIGHT_GRAY, g2);
        String q = engine.getQuizSystem().getQuestionText();
        g2.setFont(new Font(EMOJI_FONT, Font.BOLD, 26)); g2.setColor(Color.WHITE);
        g2.drawString(q, (getWidth() - g2.getFontMetrics().stringWidth(q)) / 2, 310);
        List<String> opts = engine.getQuizSystem().getOptions();
        int startIdx = 0;
        if (engine.difficulty == GameEngine.Difficulty.HARD && engine.getQuizSystem().getCurrentType() == QuizSystem.Type.COUNT) {
            int tidx = Integer.parseInt(opts.get(0).substring(5));
            if (engine.taskImages[tidx] != null) drawCharacter(g2, engine.taskImages[tidx], 500, 400, 80, true);
            startIdx = 1;
        }
        List<String> drawOpts = opts.subList(startIdx, opts.size());
        int num = drawOpts.size();
        int bW = 120, gap = 60;
        int totalW = num * bW + (num - 1) * gap;
        int sX = (getWidth() - totalW) / 2 + bW / 2;
        for (int i = 0; i < num; i++) {
            int x = sX + i * (bW + gap);
            g2.setColor(new Color(255, 255, 255, 50)); g2.fillRoundRect(x - 60, 460, bW, 150, 20, 20);
            String opt = drawOpts.get(i);
            if (opt.startsWith("TASK_") || opt.startsWith("DIST_")) {
                int tidx = Integer.parseInt(opt.substring(5));
                Image img = opt.startsWith("TASK_") ? engine.taskImages[tidx] : engine.distractorImages[tidx];
                if (img != null) drawCharacter(g2, img, x, 535, 90, true);
            } else {
                g2.setColor(Color.WHITE); g2.setFont(new Font(EMOJI_FONT, Font.PLAIN, 65));
                g2.drawString(opt, x - g2.getFontMetrics().stringWidth(opt) / 2, 555);
            }
            g2.setColor(Color.WHITE); g2.setFont(new Font(FONT_NAME, Font.BOLD, 22));
            g2.drawString("[" + (i + 1) + "]", x - 15, 645);
        }
    }

    private void drawRoundEnd(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRect(0, 0, getWidth(), getHeight());
        int cardW = 500, cardH = 350;
        int cx = (getWidth() - cardW) / 2;
        int cy = (getHeight() - cardH) / 2;
        g2.setColor(new Color(255, 255, 255, 230));
        g2.fillRoundRect(cx, cy, cardW, cardH, 30, 30);
        g2.setColor(BLUE_BTN_COLOR);
        g2.setStroke(new BasicStroke(4));
        g2.drawRoundRect(cx, cy, cardW, cardH, 30, 30);
        drawCentered(g2, "--- 回合 " + (engine.currentRound - 1) + " 完成 ---", cy + 60, 32, Color.DARK_GRAY);
        g2.setFont(new Font(FONT_NAME, Font.BOLD, 20));
        g2.setColor(Color.GRAY);
        g2.drawString("【 目前累積得分 】", cx + 50, cy + 110);
        g2.setFont(new Font(FONT_NAME, Font.BOLD, 24));
        g2.setColor(Color.BLACK);
        g2.drawString(engine.p1Name + "：", cx + 70, cy + 160);
        g2.setColor(new Color(41, 128, 185));
        g2.drawString(engine.p1Score + " pts", cx + 320, cy + 160);
        g2.setColor(Color.BLACK);
        g2.drawString(engine.p2Name + "：", cx + 70, cy + 205);
        g2.setColor(new Color(41, 128, 185));
        g2.drawString(engine.p2Score + " pts", cx + 320, cy + 205);
        g2.setColor(new Color(231, 76, 60));
        g2.setFont(new Font(FONT_NAME, Font.BOLD, 22));
        String nextDiff = "下一回合難度：" + engine.difficulty;
        g2.drawString(nextDiff, (getWidth() - g2.getFontMetrics().stringWidth(nextDiff)) / 2, cy + 265);
        Rectangle promptBtn = new Rectangle(cx + 100, cy + 290, 300, 40);
        g2.setColor(new Color(46, 204, 113));
        g2.fillRoundRect(promptBtn.x, promptBtn.y, promptBtn.width, promptBtn.height, 10, 10);
        g2.setColor(Color.WHITE);
        drawTextInRect(g2, "按一下鍵盤開啟下回合", promptBtn, 16);
    }

    private void drawGameOver(Graphics2D g2) {
        g2.setColor(new Color(34, 139, 34)); g2.fillRect(0, 400, getWidth(), 300);
        g2.setColor(new Color(70, 70, 70)); g2.fillRect(0, 460, getWidth(), 60); g2.fillRect(0, 560, getWidth(), 60);
        for (Confetti c : engine.confettiList) c.draw(g2);
        g2.setColor(new Color(255, 255, 255, 180)); g2.fillRect(0, 0, getWidth(), getHeight());
        String winMsg = engine.p1Score > engine.p2Score ? engine.p1Name + " 獲勝！" : (engine.p2Score > engine.p1Score ? engine.p2Name + " 獲勝！" : "平手！");
        drawCentered(g2, "最終結果", 150, 60, Color.BLACK);
        drawCentered(g2, winMsg, 240, 50, Color.RED);
        double swayAngle = Math.sin(engine.animTick * 2.0) * 0.15;
        Graphics2D gP2 = (Graphics2D) g2.create();
        gP2.rotate(swayAngle, 350, 500 - 80);
        drawCharacter(gP2, engine.p2Icon, 350, 500, 160, false);
        gP2.dispose();
        Graphics2D gP1 = (Graphics2D) g2.create();
        gP1.rotate(-swayAngle, 650, 500 - 80);
        drawCharacter(gP1, engine.p1Icon, 650, 500, 160, false);
        gP1.dispose();
        drawCentered(g2, engine.p2Name + ": " + engine.p2Score + "  |  " + engine.p1Name + ": " + engine.p1Score, 560, 30, Color.BLACK);
        drawCentered(g2, "請點擊右上角關閉遊戲", 640, 18, Color.GRAY, false);
    }

    private void drawCentered(Graphics2D g2, String t, int y, int s, Color c) {
        drawCentered(g2, t, y, s, c, true);
    }
    private void drawCentered(Graphics2D g2, String t, int y, int s, Color c, boolean b) {
        g2.setFont(new Font(FONT_NAME, b ? Font.BOLD : Font.PLAIN, s)); g2.setColor(c);
        g2.drawString(t, (getWidth() - g2.getFontMetrics().stringWidth(t)) / 2, y);
    }
    private void drawCentered(String t, int y, int s, Color c, Graphics2D g2) {
        drawCentered(g2, t, y, s, c, true);
    }
    private void drawTextInRect(Graphics2D g2, String t, Rectangle r, int s) {
        g2.setFont(new Font(FONT_NAME, Font.BOLD, s)); FontMetrics fm = g2.getFontMetrics();
        g2.drawString(t, r.x + (r.width - fm.stringWidth(t)) / 2, r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent());
    }
}

class SkyObject {
    float x, y, speed; String content;
    public SkyObject(float x, float y, float speed, String content) {
        this.x = x; this.y = y; this.speed = speed; this.content = content;
    }
}