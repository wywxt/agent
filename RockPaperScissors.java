import java.util.Random;
import java.util.Scanner;

public class RockPaperScissors {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        Random random = new Random();
        String[] choices = {"石头", "剪刀", "布"};

        System.out.println("=== 石头剪刀布游戏 ===");
        System.out.println("输入 1=石头, 2=剪刀, 3=布, 0=退出");

        int wins = 0, losses = 0, draws = 0;

        while (true) {
            System.out.print("\n请出拳: ");
            String input = scanner.nextLine().trim();

            if (input.equals("0")) {
                break;
            }

            int player;
            try {
                player = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.println("无效输入，请输入 1、2、3 或 0");
                continue;
            }

            if (player < 1 || player > 3) {
                System.out.println("无效输入，请输入 1、2、3 或 0");
                continue;
            }

            int computer = random.nextInt(3) + 1;

            System.out.println("你出: " + choices[player - 1]);
            System.out.println("电脑出: " + choices[computer - 1]);

            // 结果判定: 1石头 2剪刀 3布, 循环克制关系 石头>剪刀>布>石头
            // 玩家胜当 (player - computer + 3) % 3 == 2
            int result = (player - computer + 3) % 3;
            if (result == 0) {
                System.out.println("结果: 平局！");
                draws++;
            } else if (result == 2) {
                System.out.println("结果: 你赢了！");
                wins++;
            } else {
                System.out.println("结果: 你输了！");
                losses++;
            }

            System.out.printf("当前战绩: %d 胜 / %d 负 / %d 平%n", wins, losses, draws);
        }

        System.out.println("\n游戏结束！最终战绩: " + wins + " 胜 / " + losses + " 负 / " + draws + " 平");
        scanner.close();
    }
}
